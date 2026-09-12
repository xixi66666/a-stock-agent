// ECharts renders to canvas, so read the same semantic colors used by the UI.
export function chartTheme() {
  const styles = getComputedStyle(document.documentElement);
  const color = (name) => styles.getPropertyValue(`--${name}`).trim();
  return {
    ink: color("ink"), surface: color("surface"), body: color("body"),
    muted: color("muted"), hairline: color("hairline"), strongHairline: color("hairline-strong"),
    accent: color("accent"), accentFill: color("accent-fill"),
    up: color("up"), down: color("down"),
  };
}
