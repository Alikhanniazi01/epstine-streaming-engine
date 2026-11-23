const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const yts = require('yt-search');
const fetch = require('node-fetch');
const spotifyUrlInfo = require('spotify-url-info')(fetch);

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
app.use(express.static(path.join(__dirname, 'public')));

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
    const verifyRes = await fetch(`https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(credential)}`);
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

// Global YouTube / YouTube Music Search (No API Key Required)
app.get('/api/search', async (req, res) => {
  const q = String(req.query.q || '').trim();
  if (!q) return res.status(400).json({ error: 'Missing query parameter q' });

  try {
    const result = await yts(q + ' audio');
    const tracks = (result.videos || []).slice(0, 15).map((v) => ({
      videoId: v.videoId,
      title: v.title,
      artist: v.author?.name?.replace(' - Topic', '') || 'Unknown Artist',
      duration: v.seconds || 0,
      thumbnail: v.thumbnail || `https://i.ytimg.com/vi/${v.videoId}/hqdefault.jpg`
    }));
    res.json({ tracks });
  } catch (err) {
    res.status(500).json({ error: 'Search failed: ' + err.message });
  }
});

// Auto-Import Any Public Spotify Playlist URL -> Match Every Track on YouTube
app.post('/api/import-spotify', async (req, res) => {
  const url = String(req.body?.url || '').trim();
  if (!url || !url.includes('spotify.com/playlist/')) {
    return res.status(400).json({ error: 'Please provide a valid public Spotify playlist URL.' });
  }

  try {
    const [details, rawTracks] = await Promise.all([
      spotifyUrlInfo.getDetails(url),
      spotifyUrlInfo.getTracks(url)
    ]);

    const playlistName = details?.preview?.title || 'Imported Spotify Playlist';
    const limited = (rawTracks || []).slice(0, 50);
    const matched = [];

    for (const item of limited) {
      const title = item.name || item.title || '';
      const artist = item.artist || (Array.isArray(item.artists) ? item.artists.map(a => a.name).join(', ') : '');
      if (!title) continue;
      try {
        const r = await yts(`${title} ${artist} official audio`);
        const top = r.videos && r.videos[0];
        if (top) {
          matched.push({
            videoId: top.videoId,
            title,
            artist: artist || top.author?.name || 'Unknown Artist',
            duration: top.seconds || 0,
            thumbnail: top.thumbnail || `https://i.ytimg.com/vi/${top.videoId}/hqdefault.jpg`
          });
        }
      } catch (_e) {}
    }

    res.json({ name: playlistName, totalFound: limited.length, tracks: matched });
  } catch (err) {
    res.status(500).json({ error: 'Could not read Spotify playlist (make sure it is set to Public): ' + err.message });
  }
});

// SPA fallback
app.get('*', (_req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.listen(PORT, () => {
  console.log(`Spotify Cloud PWA running on port ${PORT} (Google Auth: ${GOOGLE_CLIENT_ID ? 'ON' : 'OFF'})`);
});
