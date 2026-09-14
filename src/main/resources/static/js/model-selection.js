/*
 * 全局模型选择状态：头部选择器写入，各生成模块读取。
 * 只保存模型 ID；模型目录与连通性始终来自后端，不在浏览器缓存敏感字段。
 */
const KEY = "astock.selectedModelId";

export function getSelectedModelId() {
  try {
    return window.localStorage.getItem(KEY) || null;
  } catch {
    return null;
  }
}

export function setSelectedModelId(modelId) {
  const previous = getSelectedModelId();
  try {
    if (modelId) window.localStorage.setItem(KEY, modelId);
    else window.localStorage.removeItem(KEY);
  } catch {
    /* 隐私模式下忽略存储失败，内存状态仍可用 */
  }
  if (previous !== (modelId || null)) {
    window.dispatchEvent(new CustomEvent("model-selection-change", { detail: { modelId } }));
  }
}
