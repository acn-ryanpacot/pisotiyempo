// Probes every market-data source the backend depends on and prints what came back.
// Usage: node scripts/probe.mjs [EODHD_API_KEY]
// Run this after deploying or whenever a fallback source seems broken.

const UA = "Mozilla/5.0 (Linux; Android 14) PisoTiyempo/1.0";
const eodhdKey = process.argv[2] ?? process.env.EODHD_API_KEY;

async function probe(name, fn) {
  try {
    const result = await fn();
    console.log(`OK   ${name}: ${result}`);
  } catch (err) {
    console.log(`FAIL ${name}: ${err.message}`);
  }
}

async function get(url, init = {}) {
  const res = await fetch(url, { ...init, headers: { "User-Agent": UA, ...(init.headers ?? {}) } });
  if (!res.ok) throw new Error(`HTTP ${res.status} ${url}`);
  return res;
}

await probe("phisix snapshot", async () => {
  const json = await (await get("https://phisix-api3.appspot.com/stocks.json")).json();
  return `${json.stocks.length} stocks as of ${json.as_of}; first=${JSON.stringify(json.stocks[0])}`;
});

await probe("yahoo PSEI.PS", async () => {
  const json = await (await get("https://query1.finance.yahoo.com/v8/finance/chart/PSEI.PS?range=1mo&interval=1d")).json();
  const r = json.chart.result[0];
  return `${r.timestamp.length} bars, last close ${r.indicators.quote[0].close.at(-1)}`;
});

await probe("pse edge company lookup", async () => {
  const json = await (await get("https://edge.pse.com.ph/autoComplete/searchCompanyNameSymbol.ax?term=JFC")).json();
  return JSON.stringify(json);
});

let securityId = null;
await probe("pse edge stockData security id", async () => {
  const html = await (await get("https://edge.pse.com.ph/companyPage/stockData.do?cmpy_id=86")).text();
  const select = html.match(/<select[^>]*name="security_id"[^>]*>([\s\S]*?)<\/select>/i);
  const options = select ? [...select[1].matchAll(/<option[^>]*value="(\d+)"[^>]*>\s*([^<]+?)\s*</gi)] : [];
  securityId = options[0]?.[1] ?? null;
  return `options=${JSON.stringify(options.map((o) => [o[1], o[2]]))}`;
});

await probe("pse edge chart history", async () => {
  if (!securityId) throw new Error("no security id from previous probe");
  const res = await get("https://edge.pse.com.ph/common/DisclosureCht.ax", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ cmpy_id: "86", security_id: securityId, startDate: "01-01-2026", endDate: "12-31-2026" }),
  });
  const json = await res.json();
  return `${json.chartData?.length} bars; first=${JSON.stringify(json.chartData?.[0])}`;
});

await probe("pse edge disclosures", async () => {
  const body = new URLSearchParams({ keyword: "86", tmplNm: "", sortType: "", dateSortType: "DESC", cmpySortType: "ASC", pageNo: "1" });
  const html = await (await get("https://edge.pse.com.ph/companyDisclosures/search.ax", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  })).text();
  const rows = [...html.matchAll(/openPopup\('([0-9a-f]+)'\)[^>]*>\s*([^<]+)</gi)];
  return `${rows.length} rows; first=${JSON.stringify(rows[0]?.slice(1))}; bytes=${html.length}`;
});

await probe("pse edge dividends", async () => {
  const html = await (await get("https://edge.pse.com.ph/companyPage/dividends_and_rights_list.ax?DividendsOrRights=Dividends&cmpy_id=86")).text();
  const rows = [...html.matchAll(/<tr[^>]*>([\s\S]*?)<\/tr>/gi)].map((m) =>
    [...m[1].matchAll(/<t[dh][^>]*>([\s\S]*?)<\/t[dh]>/gi)].map((c) => c[1].replace(/<[^>]+>/g, "").trim()),
  );
  return `${rows.length} rows; head=${JSON.stringify(rows.slice(0, 3))}`;
});

if (eodhdKey) {
  await probe("eodhd bulk last day PSE", async () => {
    const json = await (await get(`https://eodhd.com/api/eod-bulk-last-day/PSE?api_token=${eodhdKey}&fmt=json`)).json();
    return `${json.length} rows; first=${JSON.stringify(json[0])}`;
  });
  await probe("eodhd PSEi index", async () => {
    const json = await (await get(`https://eodhd.com/api/eod/PSEI.INDX?api_token=${eodhdKey}&fmt=json&from=2026-09-01`)).json();
    return `${json.length} bars; last=${JSON.stringify(json.at(-1))}`;
  });
} else {
  console.log("SKIP eodhd: pass an API key as the first argument to test it");
}
