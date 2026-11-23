# Spotify Cloud PWA (24/7 Mobile Music App — No Laptop Needed)

This package gives you your own **24/7 cloud-hosted Spotify-style mobile app** with:
- **Global music search** (powered by YouTube/YouTube Music via `yt-search`, **no API key needed**)
- **Automatic Spotify Playlist Link Import** (paste any public `https://open.spotify.com/playlist/...` link and every song is matched and added automatically)
- **Installable Mobile App (PWA)** for Android & iPhone ("Add to Home Screen")

## Deploy Free in 5 Minutes (Render.com)
1. Create a free GitHub repository and upload all files from this folder.
2. Go to [render.com](https://render.com), sign in with GitHub, click **New + -> Web Service**, and select your repo.
3. Render automatically detects `render.yaml` (`npm install` / `npm start`, **Free** tier). Click **Create Web Service**.
4. Once live, open your `https://<your-app>.onrender.com` link on your phone:
   - **Android (Chrome):** Tap the menu (`⋮`) -> **Add to Home screen** -> **Install**.
   - **iPhone (Safari):** Tap **Share** -> **Add to Home Screen**.

## Tip for 24/7 Instant Wakeup
Render's free tier sleeps after 15 minutes of inactivity and takes ~20 seconds to wake up on the first request. You can keep it awake 24/7 by pointing a free uptime monitor (such as UptimeRobot) at `https://<your-app>.onrender.com/api/health` every 10 minutes.
