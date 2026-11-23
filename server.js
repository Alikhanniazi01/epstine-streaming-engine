const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const https = require('https');
const zlib = require('zlib');
const yts = require('yt-search');
const nativeFetch = globalThis.fetch || require('node-fetch');

function fetchTextHttps(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9'
      }
    }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        return resolve(fetchTextHttps(res.headers.location));
      }
      if (res.statusCode !== 200) return reject(new Error('HTTP ' + res.statusCode));
      let body = '';
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => resolve(body));
    });
    req.on('error', reject);
    req.setTimeout(15000, () => { req.destroy(new Error('Request timed out')); });
  });
}

const app = express();
const PORT = process.env.PORT || 3000;
const GOOGLE_CLIENT_ID = (process.env.GOOGLE_CLIENT_ID || '').trim();
const SESSION_SECRET = process.env.SESSION_SECRET || crypto.createHash('sha256').update(GOOGLE_CLIENT_ID || 'spotify-cloud-local-secret').digest('hex');

const DATA_DIR = path.join(__dirname, 'data');
const USERS_FILE = path.join(DATA_DIR, 'users.json');

function loadUsersDB() {
  try {
    if (!fs.existsSync(USERS_FILE)) return {};
    return JSON.parse(fs.readFileSync(USERS_FILE, 'utf8'));
  } catch (_e) {
    return {};
  }
}

function saveUsersDB(db) {
  try {
    if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(USERS_FILE, JSON.stringify(db, null, 2), 'utf8');
  } catch (err) {
    console.error('Failed to save users DB:', err.message);
  }
}

function signSession(payload) {
  const data = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const sig = crypto.createHmac('sha256', SESSION_SECRET).update(data).digest('base64url');
  return `${data}.${sig}`;
}

function verifySession(token) {
  if (!token || !token.includes('.')) return null;
  const [data, sig] = token.split('.');
  const expected = crypto.createHmac('sha256', SESSION_SECRET).update(data).digest('base64url');
  if (sig !== expected) return null;
  try {
    const parsed = JSON.parse(Buffer.from(data, 'base64url').toString('utf8'));
    if (parsed.exp && Date.now() > parsed.exp) return null;
    return parsed;
  } catch (_e) {
    return null;
  }
}

function requireAuth(req, res, next) {
  const auth = req.headers.authorization || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  const session = verifySession(token);
  if (!session || !session.sub) {
    return res.status(401).json({ error: 'Not signed in' });
  }
  req.user = session;
  next();
}

app.use(cors());
app.use(express.json({ limit: '2mb' }));

// Built-in gzip compression for fast mobile data loads (zero extra dependencies)
app.use((req, res, next) => {
  const accept = req.headers['accept-encoding'] || '';
  if (!accept.includes('gzip')) return next();
  const origJson = res.json.bind(res);
  res.json = (body) => {
    const raw = Buffer.from(JSON.stringify(body));
    if (raw.length < 1024) return origJson(body);
    res.setHeader('Content-Encoding', 'gzip');
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.end(zlib.gzipSync(raw));
  };
  next();
});

app.use(express.static(path.join(__dirname, 'public'), { maxAge: '5m' }));

// In-memory search cache (30-min TTL, up to 500 queries -> <5ms repeat searches)
const searchCache = new Map();
const CACHE_TTL_MS = 30 * 60 * 1000;

// Health check for Render / Railway 24/7 monitoring
app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', service: 'spotify-cloud-pwa', googleAuthEnabled: !!GOOGLE_CLIENT_ID });
});

// Public Auth Config (exposes only the public Google OAuth Client ID)
app.get('/api/auth/config', (_req, res) => {
  res.json({ googleClientId: GOOGLE_CLIENT_ID });
});

// Verify Google Sign-In ID Token and return signed session + saved user library
app.post('/api/auth/google', async (req, res) => {
  const credential = String(req.body?.credential || '').trim();
  if (!credential) return res.status(400).json({ error: 'Missing Google credential' });
  if (!GOOGLE_CLIENT_ID) return res.status(503).json({ error: 'GOOGLE_CLIENT_ID is not configured on the server yet.' });

  try {
    const verifyRes = await nativeFetch(`https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(credential)}`);
    if (!verifyRes.ok) return res.status(401).json({ error: 'Invalid Google token' });
    const info = await verifyRes.json();

    if (info.aud !== GOOGLE_CLIENT_ID) {
      return res.status(401).json({ error: 'Google token audience mismatch' });
    }

    const userProfile = {
      sub: info.sub,
      email: info.email || '',
      name: info.name || info.given_name || 'Listener',
      picture: info.picture || ''
    };

    const db = loadUsersDB();
    if (!db[userProfile.sub]) {
      db[userProfile.sub] = {
        profile: userProfile,
        data: { likedIds: [], recentIds: [], customPlaylists: [], extraTracks: [], volume: 80, eqGains: [0, 0, 0, 0, 0] },
        updatedAt: Date.now()
      };
      saveUsersDB(db);
    } else {
      db[userProfile.sub].profile = userProfile;
      saveUsersDB(db);
    }

    const token = signSession({
      ...userProfile,
      exp: Date.now() + 30 * 24 * 60 * 60 * 1000 // 30 days
    });

    res.json({
      token,
      user: userProfile,
      data: db[userProfile.sub].data
    });
  } catch (err) {
    res.status(500).json({ error: 'Google sign-in verification failed: ' + err.message });
  }
});

// Load signed-in user's saved library & preferences
app.get('/api/user/data', requireAuth, (req, res) => {
  const db = loadUsersDB();
  const entry = db[req.user.sub];
  res.json({
    user: { sub: req.user.sub, email: req.user.email, name: req.user.name, picture: req.user.picture },
    data: entry ? entry.data : null
  });
});

// Save signed-in user's library & preferences
app.post('/api/user/data', requireAuth, (req, res) => {
  const incoming = req.body?.data;
  if (!incoming || typeof incoming !== 'object') {
    return res.status(400).json({ error: 'Missing data payload' });
  }

  const db = loadUsersDB();
  db[req.user.sub] = {
    profile: { sub: req.user.sub, email: req.user.email, name: req.user.name, picture: req.user.picture },
    data: {
      likedIds: Array.isArray(incoming.likedIds) ? incoming.likedIds.slice(0, 1000) : [],
      recentIds: Array.isArray(incoming.recentIds) ? incoming.recentIds.slice(0, 50) : [],
      customPlaylists: Array.isArray(incoming.customPlaylists) ? incoming.customPlaylists.slice(0, 100) : [],
      extraTracks: Array.isArray(incoming.extraTracks) ? incoming.extraTracks.slice(0, 500) : [],
      volume: typeof incoming.volume === 'number' ? incoming.volume : 80,
      eqGains: Array.isArray(incoming.eqGains) ? incoming.eqGains.slice(0, 5) : [0, 0, 0, 0, 0]
    },
    updatedAt: Date.now()
  };
  saveUsersDB(db);
  res.json({ status: 'saved', updatedAt: db[req.user.sub].updatedAt });
});

// Global YouTube / YouTube Music Search with 30-min cache (No API Key Required)
app.get('/api/search', async (req, res) => {
  const q = String(req.query.q || '').trim();
  if (!q) return res.status(400).json({ error: 'Missing query parameter q' });

  const key = q.toLowerCase();
  const cached = searchCache.get(key);
  if (cached && (Date.now() - cached.ts) < CACHE_TTL_MS) {
    return res.json({ tracks: cached.tracks, cached: true });
  }

  try {
    const result = await yts(q + ' audio');
    const tracks = (result.videos || []).slice(0, 15).map((v) => ({
      videoId: v.videoId,
      title: v.title,
      artist: v.author?.name?.replace(' - Topic', '') || 'Unknown Artist',
      duration: v.seconds || 0,
      thumbnail: v.thumbnail || `https://i.ytimg.com/vi/${v.videoId}/hqdefault.jpg`
    }));
    if (searchCache.size >= 500) {
      const oldestKey = searchCache.keys().next().value;
      searchCache.delete(oldestKey);
    }
    searchCache.set(key, { ts: Date.now(), tracks });
    res.json({ tracks, cached: false });
  } catch (err) {
    res.status(500).json({ error: 'Search failed: ' + err.message });
  }
});

// Helper: run async tasks with bounded concurrency so Render never times out
async function mapWithConcurrency(items, limit, worker) {
  const results = new Array(items.length);
  let nextIdx = 0;
  async function runner() {
    while (nextIdx < items.length) {
      const i = nextIdx++;
      results[i] = await worker(items[i], i);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, runner));
  return results.filter(Boolean);
}

// Auto-Import Any Public Spotify Playlist or Album URL (including spotify.link mobile short links)
app.post('/api/import-spotify', async (req, res) => {
  let rawUrl = String(req.body?.url || '').trim();
  if (!rawUrl || (!rawUrl.includes('spotify.com') && !rawUrl.includes('spotify.link') && !rawUrl.includes('spoti.fi'))) {
    return res.status(400).json({ error: 'Please paste a valid Spotify playlist or album link.' });
  }

  try {
    // Follow mobile short-links (e.g. spotify.link / spoti.fi) to get canonical open.spotify.com URL
    if (!rawUrl.includes('open.spotify.com/')) {
      const redir = await nativeFetch(rawUrl, { redirect: 'follow' });
      if (redir.url) rawUrl = redir.url;
    }

    const match = rawUrl.match(/spotify\.com\/(?:intl-[a-z-]+\/)?(playlist|album)\/([a-zA-Z0-9]+)/);
    if (!match) {
      return res.status(400).json({ error: 'Could not find a Spotify playlist or album ID in that link.' });
    }
    const [, kind, spotifyId] = match;

    // Fetch Spotify public embed page via native Node https (avoids node-fetch v2 TLS drops)
    const html = await fetchTextHttps(`https://open.spotify.com/embed/${kind}/${spotifyId}`);
    const ndMatch = html.match(/<script id="__NEXT_DATA__" type="application\/json">([\s\S]*?)<\/script>/);
    if (!ndMatch) throw new Error('Could not parse playlist data — make sure the playlist is set to Public.');

    const nextData = JSON.parse(ndMatch[1]);
    const entity = nextData?.props?.pageProps?.state?.data?.entity || {};
    const playlistName = entity.name || entity.title || 'Imported Spotify Playlist';
    const rawTracks = (entity.trackList || []).slice(0, 100);

    if (rawTracks.length === 0) {
      return res.status(404).json({ error: 'No tracks found. Make sure your Spotify playlist is Public.' });
    }

    const coverUrl = entity.coverArt?.sources?.[0]?.url || '';
    const tracks = rawTracks.map((item, idx) => {
      const title = String(item.title || item.name || '').trim();
      const artist = String(item.subtitle || '').replace(/\u00a0/g, ' ').trim();
      if (!title) return null;
      return {
        spotifyUri: item.uri || `sp_${spotifyId}_${idx}`,
        title,
        artist: artist || 'Unknown Artist',
        duration: Math.round((item.duration || 0) / 1000),
        previewUrl: item.audioPreview?.url || '',
        thumbnail: coverUrl
      };
    }).filter(Boolean);

    res.json({ name: playlistName, totalFound: tracks.length, tracks });
  } catch (err) {
    res.status(500).json({ error: 'Could not import Spotify playlist: ' + err.message });
  }
});

// Privacy Policy page (for Google OAuth production publishing)
app.get('/privacy', (_req, res) => {
  res.type('html').send(`<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Privacy Policy</title><style>body{background:#121212;color:#fff;font-family:system-ui,sans-serif;max-width:680px;margin:40px auto;padding:0 20px;line-height:1.6}a{color:#1DB954}</style></head><body><h1>Privacy Policy</h1><p>This app uses Google Sign-In solely to identify your profile (name, email, and profile picture) so your playlists, liked songs, and playback preferences stay saved under your account. We do not sell or share your data with third parties.</p><p><a href="/">Back to App</a></p></body></html>`);
});

// SPA fallback
app.get('*', (_req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.listen(PORT, () => {
  console.log(`Spotify Cloud PWA running on port ${PORT} (Google Auth: ${GOOGLE_CLIENT_ID ? 'ON' : 'OFF'})`);
});
