// Presentation-only playback state; market data and research remain independent.
const video = document.querySelector(".ambient-video");
const toggle = document.querySelector("#background-toggle");
const preferenceKey = "research-background-paused";
const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
let pausedByUser = false;
let motionOverride = false;
let failed = false;
try { pausedByUser = localStorage.getItem(preferenceKey) === "true"; } catch { /* Storage may be disabled. */ }

function updateControl() {
  const paused = video.paused;
  const label = failed ? "动态背景暂不可用" : paused ? "播放动态背景" : "暂停动态背景";
  toggle.disabled = failed;
  toggle.setAttribute("aria-label", label);
  toggle.title = label;
  toggle.innerHTML = `<i data-lucide="${paused ? "play" : "pause"}" aria-hidden="true"></i>`;
  window.lucide?.createIcons({ attrs: { "stroke-width": 1.8 } });
}

async function play() {
  if (!video.getAttribute("src")) video.src = video.dataset.src;
  video.muted = true;
  video.playbackRate = 0.75;
  try { await video.play(); } catch { /* Poster remains visible if autoplay is blocked. */ }
  if (!shouldPlay()) video.pause();
  updateControl();
}

function shouldPlay() {
  return !failed && !pausedByUser && document.visibilityState !== "hidden"
    && (!reducedMotion.matches || motionOverride);
}

function syncPlayback() {
  if (shouldPlay()) void play();
  else video.pause();
  updateControl();
}

toggle.addEventListener("click", () => {
  pausedByUser = !video.paused;
  motionOverride = !pausedByUser;
  try { localStorage.setItem(preferenceKey, String(pausedByUser)); } catch { /* Playback still works. */ }
  syncPlayback();
});
reducedMotion.addEventListener("change", () => { motionOverride = false; syncPlayback(); });
document.addEventListener("visibilitychange", syncPlayback);
video.addEventListener("play", updateControl);
video.addEventListener("pause", updateControl);
video.addEventListener("error", () => {
  failed = true;
  video.pause();
  video.removeAttribute("src");
  video.load();
  updateControl();
});
toggle.hidden = false;
updateControl();
syncPlayback();
