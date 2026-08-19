/**
 * Last Notif — settings UI controller
 *
 * Communicates with the native layer via window.NativeBridge
 * (injected by LastNotifNativeBridge.java).
 *
 * All settings are saved immediately on change (no Save button).
 */

// ─── DOM refs ─────────────────────────────────────────────────

const $ = id => document.getElementById(id);

const els = {
  btnToggleService: $('btn-toggle-service'),
  serviceBtnLabel:  $('service-btn-label'),
  nowPlayingBar:    $('now-playing-bar'),
  npText:           $('np-text'),

  username:    $('input-username'),
  songUpdate:  $('toggle-song-update'),
  interval:    $('toggle-interval'),
  intervalCfg: $('interval-config'),
  intervalMin: $('input-interval'),
  mainFmt:     $('input-main-fmt'),
  subFmt:      $('input-sub-fmt'),
  lyrics:      $('toggle-lyrics'),
  lyricsWarn:  $('lyrics-warning'),

  cardUsername:      $('card-username'),
  permissionWarning: $('permission-warning'),
  btnGrantPerm:      $('btn-grant-permission'),
  notifPermWarning:  $('notif-permission-warning'),
  btnGrantNotifPerm: $('btn-grant-notif-permission'),
  sourceDevice:      $('source-device'),
  sourceLastfm:      $('source-lastfm'),
  sourceMixed:       $('source-mixed'),
};

// ─── State ────────────────────────────────────────────────────

let settings = {
  username:        '',
  notifySongUpdate: true,
  intervalEnabled:  false,
  intervalMinutes:  5,
  notifMainFormat:  '{song_name}',
  notifSubFormat:   '{artist}',
  lyricsEnabled:    false,
  trackSource:      'mixed',
};

let serviceRunning = false;
let npPollTimer    = null;

// ─── Init ─────────────────────────────────────────────────────

function init() {
  loadSettings();
  bindEvents();
  checkNotificationPermission();
  syncServiceStatus();
  startNpPoll();
}

// ─── Load / render settings ───────────────────────────────────

function loadSettings() {
  if (!window.NativeBridge) return;

  try {
    const raw = NativeBridge.getSettings();
    const s   = JSON.parse(raw);
    Object.assign(settings, s);
  } catch (e) {
    console.warn('loadSettings failed:', e);
  }

  renderSettings();
}

function renderSettings() {
  els.username.value    = settings.username;
  els.songUpdate.checked  = settings.notifySongUpdate;
  els.interval.checked    = settings.intervalEnabled;
  els.intervalMin.value   = settings.intervalMinutes;
  els.mainFmt.value       = settings.notifMainFormat;
  els.subFmt.value        = settings.notifSubFormat;
  els.lyrics.checked      = settings.lyricsEnabled;

  if (settings.trackSource === 'device') {
    els.sourceDevice.checked = true;
  } else if (settings.trackSource === 'lastfm') {
    els.sourceLastfm.checked = true;
  } else {
    els.sourceMixed.checked = true;
  }

  // Conditional visibility
  els.cardUsername.classList.toggle('hidden', settings.trackSource === 'device');
  els.intervalCfg.classList.toggle('hidden', !settings.intervalEnabled);
  els.lyricsWarn.classList.toggle('hidden', !settings.lyricsEnabled);

  // Lyrics forces song-update on
  if (settings.lyricsEnabled) {
    els.songUpdate.checked  = true;
    els.songUpdate.disabled = true;
  } else {
    els.songUpdate.disabled = false;
  }
}

// ─── Save ─────────────────────────────────────────────────────

function saveSettings() {
  if (!window.NativeBridge) return;
  NativeBridge.saveSettings(JSON.stringify(settings));
}

// ─── Event bindings ───────────────────────────────────────────

function checkNotificationPermission() {
  if (!window.NativeBridge) return;
  const requiresAccess = (settings.trackSource === 'device' || settings.trackSource === 'mixed');
  if (requiresAccess && !NativeBridge.hasNotificationAccess()) {
    els.permissionWarning.classList.remove('hidden');
  } else {
    els.permissionWarning.classList.add('hidden');
  }

  // System notification permission (Android 13+)
  const notifGranted = NativeBridge.hasNotificationPermission();
  if (typeof notifGranted === 'boolean' && !notifGranted) {
    els.notifPermWarning.classList.remove('hidden');
  } else {
    els.notifPermWarning.classList.add('hidden');
  }
}

function bindEvents() {
  // Source radio buttons
  const onSourceChange = (e) => {
    settings.trackSource = e.target.value;
    els.cardUsername.classList.toggle('hidden', settings.trackSource === 'device');
    checkNotificationPermission();
    saveSettings();
    startNpPoll();
  };
  els.sourceDevice.addEventListener('change', onSourceChange);
  els.sourceLastfm.addEventListener('change', onSourceChange);
  els.sourceMixed.addEventListener('change', onSourceChange);

  // Grant permission button
  els.btnGrantPerm.addEventListener('click', () => {
    if (window.NativeBridge) {
      NativeBridge.openNotificationAccessSettings();
    }
  });

  // Grant notification permission button
  els.btnGrantNotifPerm.addEventListener('click', () => {
    if (window.NativeBridge) {
      NativeBridge.requestNotificationPermission();
    }
  });

  // Username — debounced save
  let usernameTimer;
  els.username.addEventListener('input', () => {
    clearTimeout(usernameTimer);
    settings.username = els.username.value.trim();
    usernameTimer = setTimeout(() => {
      saveSettings();
      refreshNowPlaying();
    }, 800);
  });

  // Song update toggle
  els.songUpdate.addEventListener('change', () => {
    settings.notifySongUpdate = els.songUpdate.checked;
    saveSettings();
  });

  // Interval toggle
  els.interval.addEventListener('change', () => {
    settings.intervalEnabled = els.interval.checked;
    els.intervalCfg.classList.toggle('hidden', !settings.intervalEnabled);
    saveSettings();
  });

  // Interval minutes — debounced save
  let intervalTimer;
  els.intervalMin.addEventListener('input', () => {
    clearTimeout(intervalTimer);
    intervalTimer = setTimeout(() => {
      const val = parseInt(els.intervalMin.value, 10);
      if (!isNaN(val) && val >= 1) {
        settings.intervalMinutes = val;
        saveSettings();
      }
    }, 600);
  });

  // Main format — debounced save
  let fmtTimer;
  els.mainFmt.addEventListener('input', () => {
    clearTimeout(fmtTimer);
    fmtTimer = setTimeout(() => {
      settings.notifMainFormat = els.mainFmt.value || '{song_name}';
      saveSettings();
    }, 600);
  });

  // Sub format — debounced save
  let fmtTimer2;
  els.subFmt.addEventListener('input', () => {
    clearTimeout(fmtTimer2);
    fmtTimer2 = setTimeout(() => {
      settings.notifSubFormat = els.subFmt.value || '{artist}';
      saveSettings();
    }, 600);
  });

  // Lyrics toggle
  els.lyrics.addEventListener('change', () => {
    settings.lyricsEnabled = els.lyrics.checked;
    els.lyricsWarn.classList.toggle('hidden', !settings.lyricsEnabled);

    // Force song-update on when lyrics are active
    if (settings.lyricsEnabled) {
      settings.notifySongUpdate = true;
      els.songUpdate.checked    = true;
      els.songUpdate.disabled   = true;
    } else {
      els.songUpdate.disabled = false;
    }

    saveSettings();
    startNpPoll();
  });

  // Service button
  els.btnToggleService.addEventListener('click', () => {
    if (!window.NativeBridge) return;

    if (serviceRunning) {
      NativeBridge.stopPoller();
    } else {
      if (settings.trackSource !== 'device' && !settings.username) {
        els.username.focus();
        els.username.style.borderColor = '#e85d9f';
        setTimeout(() => { els.username.style.borderColor = ''; }, 1500);
        return;
      }
      NativeBridge.startPoller();
    }

    // Give the service a moment to start/stop then re-check
    setTimeout(() => {
      syncServiceStatus();
      startNpPoll();
    }, 600);
  });
}

// ─── Service status ───────────────────────────────────────────

function syncServiceStatus() {
  if (!window.NativeBridge) return;

  serviceRunning = NativeBridge.isPollerRunning() === 'true';

  if (serviceRunning) {
    els.serviceBtnLabel.textContent = 'Stop';
    els.btnToggleService.classList.add('running');
  } else {
    els.serviceBtnLabel.textContent = 'Start';
    els.btnToggleService.classList.remove('running');
  }
}

// ─── Now-playing preview ──────────────────────────────────────

/**
 * Polls the Maru site's now-playing endpoint directly from the web layer
 * or retrieves it from the background service's active_track.json cache.
 */
async function refreshNowPlaying() {
  if (window.NativeBridge && NativeBridge.isPollerRunning() === 'true') {
    try {
      const raw = NativeBridge.getActiveTrack();
      const track = JSON.parse(raw);
      if (track && track.isPlaying && track.title) {
        if (track.lyricLine) {
          els.npText.textContent = `♪ ${track.lyricLine}`;
        } else {
          els.npText.textContent = `${track.title} — ${track.artist}`;
        }
        els.nowPlayingBar.classList.remove('hidden');
        return;
      } else {
        els.nowPlayingBar.classList.add('hidden');
        return;
      }
    } catch (e) {
      console.warn('getActiveTrack failed:', e);
    }
  }

  const user = settings.username.trim();
  if (!user || settings.trackSource === 'device') {
    els.nowPlayingBar.classList.add('hidden');
    return;
  }

  try {
    const url = `https://maruchansquigle.vercel.app/api/auth`
      + `?route=lastfm/now-playing&username=${encodeURIComponent(user)}&fast=1`;
    const res  = await fetch(url, { cache: 'no-store' });
    const data = await res.json();
    const track = data?.track;

    if (track?.nowPlaying && track?.title) {
      els.npText.textContent = `${track.title} — ${track.artist}`;
      els.nowPlayingBar.classList.remove('hidden');
    } else {
      els.nowPlayingBar.classList.add('hidden');
    }
  } catch {
    els.nowPlayingBar.classList.add('hidden');
  }
}

/** Poll now-playing dynamically. Checks local bridge frequently if service is active. */
function startNpPoll() {
  clearInterval(npPollTimer);
  refreshNowPlaying();

  const isServiceActive = (window.NativeBridge && NativeBridge.isPollerRunning() === 'true');
  const interval = isServiceActive
    ? (settings.lyricsEnabled ? 1000 : 3000)
    : 15000;

  npPollTimer = setInterval(refreshNowPlaying, interval);
}

// ─── Resume hook (called by MainActivity on onResume) ─────────

window.onAppResume = () => {
  syncServiceStatus();
  checkNotificationPermission();
  startNpPoll();
};

// ─── Boot ─────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', init);
