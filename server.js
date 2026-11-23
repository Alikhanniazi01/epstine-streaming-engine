const express = require('express');
const cors = require('cors');
const path = require('path');
const yts = require('yt-search');
const fetch = require('node-fetch');
const spotifyUrlInfo = require('spotify-url-info')(fetch);

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// Health check for Render / Railway 24/7 monitoring
app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', service: 'spotify-cloud-pwa' });
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
    const limited = (rawTracks || []).slice(0, 50); // Match up to 50 tracks per import
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
      } catch (_e) {
        // Skip track if YouTube lookup fails
      }
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
  console.log(`Spotify Cloud PWA running on port ${PORT}`);
});
