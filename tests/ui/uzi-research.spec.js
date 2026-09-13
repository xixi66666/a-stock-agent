const { test, expect } = require('@playwright/test');
const snapshot = require('./fixtures/partial-snapshot.json');

const bundle = {
  schema: 'uzi-bundle-v1', ticker: '600519', generatedAt: '2026-09-13T00:00:00Z',
  structured: {
    companyProfile: { industry: '食品饮料', business: '白酒' },
    competitors: { items: ['同行A', '同行B'] },
    earningsForecast: { eps: 2.1, revenueGrowth: 12.5 },
    structuredValuation: { pe: 25, dcf: '条件不足' },
  },
  sources: [{ dimension: '0_basic', provider: 'CNINFO', url: 'https://example.test/basic', observedAt: '2026-09-13' }],
  dataGaps: ['宏观利率尚未接入'], reportPath: null,
};
const completed = {
  id: 'uzi-demo', code: '600519', status: 'COMPLETED', stage: '研究完成', depth: 'deep',
  modelName: 'test-model', startedAt: bundle.generatedAt, updatedAt: bundle.generatedAt,
  snapshotAt: bundle.generatedAt, bundle, limitations: ['缺失项不会自动估算'], error: null,
};

async function setup(page) {
  await page.route('**/api/**', route => route.fulfill({ json: { status: 'UNAVAILABLE', payload: null, issues: [] } }));
  await page.route('**/api/stocks/*/snapshot', route => route.fulfill({ json: { ...snapshot, security: { ...snapshot.security, code: '600519' } } }));
  await page.route('**/api/uzi/status', route => route.fulfill({ json: {
    enabled: true, installed: true, pythonAvailable: true, reason: 'READY', rootPath: 'tools/uzi/UZI-Skill', python: 'python',
  } }));
  await page.route('**/api/uzi/models', route => route.fulfill({ json: [{ id: 'deepseek', modelName: 'test-model', defaultModel: true }] }));
  await page.route('**/api/uzi/latest/*', route => route.fulfill({ status: 404, json: { detail: '尚未生成 UZI 报告' } }));
  await page.route('**/api/uzi/tasks', route => {
    expect(route.request().postDataJSON()).toEqual({ code: '600519', depth: 'deep', school: 'F' });
    return route.fulfill({ json: completed });
  });
  await page.goto('/workbench.html');
  await page.locator('[data-symbol="600519"]').click();
}

test('UZI research exposes depth, school and four structured research dimensions', async ({ page }) => {
  await setup(page);
  await page.getByRole('tab', { name: 'UZI 投研' }).click();
  await expect(page.locator('#view-content')).toContainText('UZI 投研');
  await page.selectOption('#uzi-depth', 'deep');
  await page.selectOption('#uzi-school', 'F');
  await page.getByRole('button', { name: '运行 UZI 投研', exact: true }).click();
  await expect(page.locator('#uzi-output')).toContainText('公司画像');
  await expect(page.locator('#uzi-output')).toContainText('竞争对手');
  await expect(page.locator('#uzi-output')).toContainText('盈利预测');
  await expect(page.locator('#uzi-output')).toContainText('结构化估值');
  await expect(page.locator('#uzi-output')).toContainText('22 维度原始明细');
  await expect(page.locator('#uzi-output')).toContainText('宏观利率尚未接入');
  await expect(page.locator('#uzi-output')).toContainText('CNINFO');
  await expect(page.locator('#uzi-output .generation-line').first()).toContainText('生成模式：UZI 多维分析 · 模型：test-model');
});
