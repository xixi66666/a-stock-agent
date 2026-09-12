const { test, expect } = require('@playwright/test');
const baseSnapshot = require('./fixtures/partial-snapshot.json');
const report = require('./fixtures/financial-report.json');

test('HiThink primary keeps missing quote fields empty and discloses dividend source', async ({page}) => {
  const snapshot = structuredClone(baseSnapshot);
  Object.assign(snapshot.quote.payload,{peTtm:null,pb:null,peStatic:null,totalMarketValueYuan:null,quotedAt:null});
  snapshot.quote.provenance.provider='HiThink Finance';
  snapshot.quote.status='UNVERIFIED';
  const dividends = {status:'UNVERIFIED',payload:[{exDate:'2025-12-31',cashPerShareYuan:3.25,bonusPerTenShares:1,transferPerTenShares:null}],
    provenance:{provider:'HiThink Finance',sourceUrl:'https://fuyao.aicubes.cn/api/a-share/corporate-actions/adjustment-factors',fetchedAt:'2026-09-12T00:00:00Z'},issues:['源更新时间未提供']};
  snapshot.capital={status:'DEGRADED',payload:{dividends:dividends.payload,components:{dividends,
    dragonTiger:{status:'UNVERIFIED',payload:[{date:'2026-09-11',reason:'偏离值',netBuyYuan:123456.78,turnoverPercent:null}],
      provenance:{provider:'HiThink Finance'},issues:['换手率未提供']},
    legacyCapital:{status:'UNAVAILABLE',issues:['原筹码来源不可用']},
  },dragonTigerRecords:[{date:'2026-09-11',reason:'偏离值',netBuyYuan:123456.78,turnoverPercent:null}]},provenance:dividends.provenance,issues:[]};
  await page.route('**/api/**',route => route.fulfill({json:route.request().url().includes('/snapshot')?snapshot:{status:'UNAVAILABLE',enabled:false}}));
  await page.goto('/');
  await page.locator('[data-symbol="600519"]').click();
  await expect(page.locator('#quote-pe')).toHaveText('--');
  await expect(page.locator('#quote-pb')).toHaveText('--');
  await page.getByRole('tab',{name:'估值预期'}).click();
  await expect(page.locator('.metric-tile').filter({hasText:'总市值'})).toContainText('--');
  await page.getByRole('tab',{name:'资金筹码'}).click();
  await expect(page.locator('.capital-dividends')).toContainText('HiThink Finance');
  await expect(page.locator('.capital-dividends')).toContainText('3.25');
  await expect(page.locator('.capital-dividends')).toContainText('源更新时间未提供');
  await expect(page.locator('.capital-dragon-tiger')).toContainText('HiThink Finance');
  await expect(page.locator('.capital-dragon-tiger')).toContainText('12.35 万');
});

for (const viewport of [{width:1440,height:1000},{width:1024,height:768},{width:768,height:1024},{width:390,height:844}]) {
  test(`HiThink valuation preserves missing values and source at ${viewport.width}`, async ({page}) => {
    await page.setViewportSize(viewport);
    const snapshot = structuredClone(baseSnapshot);
    snapshot.valuation = {status:'HEALTHY', payload:{peTtm:-3.2,peMrq:null,pbMrq:2,psTtm:4,pcfTtm:-1},
      provenance:{provider:'HiThink Finance',sourceUrl:'https://fuyao.aicubes.cn/api/a-share/valuations/snapshot',
        providerTimestamp:'2026-09-11T07:00:00Z',fetchedAt:'2026-09-12T00:00:00Z',cached:true},issues:[]};
    await page.route('**/api/**', route => {
      if (route.request().url().includes('/snapshot')) return route.fulfill({json:snapshot});
      return route.fulfill({json:{status:'UNAVAILABLE',enabled:false,issues:['offline']}});
    });
    await page.goto('/');
    await page.locator('[data-symbol="600519"]').click();
    await page.getByRole('tab',{name:'估值预期'}).click();
    const block = page.locator('.hithink-valuation');
    await expect(block).toContainText('HiThink Finance');
    await expect(block).toContainText('-3.20');
    await expect(block.locator('.metric-tile').filter({hasText:'市盈率 MRQ'})).toContainText('--');
    await expect(block).toContainText('缓存');
    const layout = await page.evaluate(() => ({width:innerWidth, scroll:document.documentElement.scrollWidth}));
    expect(layout.scroll).toBeLessThanOrEqual(layout.width+1);
    await page.screenshot({path:`target/hithink-valuation-${viewport.width}.png`,fullPage:true});
  });
}

test('financial report discloses fallback source and missing annual fields', async ({page}) => {
  const data=structuredClone(report);
  data.latestPeriod.status='DEGRADED';
  data.latestPeriod.provenance.provider='HiThink Finance';
  data.latestPeriod.provenance.fallbackProvider='Sina Finance';
  data.latestPeriod.issues=['同花顺备用财报仅使用年报；未提供流动负债、股本和归母权益'];
  await page.route('**/api/**', route => {
    if (route.request().url().includes('/snapshot')) return route.fulfill({json:baseSnapshot});
    if (route.request().url().includes('/financial-report')) return route.fulfill({json:data});
    return route.fulfill({json:{status:'UNAVAILABLE',enabled:false,issues:['offline']}});
  });
  await page.goto('/');
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole('tab',{name:'财报分析'}).click();
  await page.getByRole('button',{name:'生成财报分析'}).click();
  await expect(page.locator('.financial-latest-period')).toContainText('仅使用年报');
  await expect(page.locator('.financial-latest-period')).toContainText('Sina Finance');
});
