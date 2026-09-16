import './interaction-tracing.js';
const icons = () => window.lucide?.createIcons();
const menu = document.querySelector('#mobile-menu');
const toggle = document.querySelector('.menu-toggle');
let closing;
function closeMenu() {
  menu.classList.remove('is-open');
  toggle.setAttribute('aria-expanded', 'false');
  clearTimeout(closing);
  closing = setTimeout(() => { menu.close(); document.body.style.overflow = ''; toggle.focus(); }, matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 300);
}
toggle.addEventListener('click', () => {
  clearTimeout(closing);
  menu.showModal();
  document.body.style.overflow = 'hidden';
  toggle.setAttribute('aria-expanded', 'true');
  requestAnimationFrame(() => menu.classList.add('is-open'));
});
menu.querySelector('.drawer-close').addEventListener('click', closeMenu);
menu.addEventListener('cancel', event => { event.preventDefault(); closeMenu(); });
menu.addEventListener('click', event => { if (event.target === menu) closeMenu(); });
matchMedia('(min-width: 768px)').addEventListener('change', event => { if (event.matches && menu.open) closeMenu(); });

const video = document.querySelector('.hero-video');
const control = document.querySelector('#video-control');
const reduced = matchMedia('(prefers-reduced-motion: reduce)');
let manuallyPaused = false;
let motionOverride = false;
let failed = false;
function updateControl() {
  const label = failed ? '背景视频暂不可用' : video.paused ? '播放背景视频' : '暂停背景视频';
  control.setAttribute('aria-label', label);
  control.title = label;
  control.disabled = failed;
  control.innerHTML = `<i data-lucide="${video.paused ? 'play' : 'pause'}" aria-hidden="true"></i>`;
  icons();
}
async function syncVideo() {
  if (failed || manuallyPaused || document.hidden || (reduced.matches && !motionOverride)) { video.pause(); updateControl(); return; }
  if (!video.getAttribute('src')) video.src = video.dataset.src;
  video.muted = true;
  try { await video.play(); } catch { /* A solid background preserves the entry when autoplay is unavailable. */ }
  if (document.hidden || manuallyPaused || (reduced.matches && !motionOverride)) video.pause();
  updateControl();
}
control.addEventListener('click', () => { manuallyPaused = !video.paused; motionOverride = !manuallyPaused; void syncVideo(); });
video.addEventListener('error', () => { failed = true; video.pause(); updateControl(); });
video.addEventListener('play', updateControl);
video.addEventListener('pause', updateControl);
reduced.addEventListener('change', () => { motionOverride = false; void syncVideo(); });
document.addEventListener('visibilitychange', () => void syncVideo());
icons();
void syncVideo();
