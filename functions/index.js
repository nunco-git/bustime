// 국토교통부 TAGO 버스 API 프록시 (인증키를 서버에만 보관)
const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");

const TAGO_KEY = defineSecret("TAGO_KEY"); // firebase functions:secrets:set TAGO_KEY (일반 인증키 Decoding 값)

const BASE = "https://apis.data.go.kr/1613000";
// 허용할 조회만 열어둠
const OPS = {
  cities:   { path: "BusSttnInfoInqireService/getCtyCodeList", params: [] },
  stops:    { path: "BusSttnInfoInqireService/getSttnNoList", params: ["cityCode", "nodeNm", "nodeNo"] },
  arrivals: { path: "ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", params: ["cityCode", "nodeId"] },
  // 정류장을 지나가는 버스(노선) 목록
  stopRoutes: { path: "BusSttnInfoInqireService/getSttnThrghRouteList", params: ["cityCode", "nodeid"] },
  // 좌표(지도 중심) 기준 500m 이내 정류소
  nearby:   { path: "BusSttnInfoInqireService/getCrdntPrxmtSttnList", params: ["gpsLati", "gpsLong"] },
  // 노선별 경유 정류장(순서대로) / 노선별 버스 위치
  routeStops: { path: "BusRouteInfoInqireService/getRouteAcctoThrghSttnList", params: ["cityCode", "routeId"], rows: 300 },
  busLoc:     { path: "BusLcInfoInqireService/getRouteAcctoBusLcList", params: ["cityCode", "routeId"], rows: 100 },
};

// 공공데이터 호출 횟수 절약용 메모리 캐시 (같은 정류장을 여러 명이 봐도 한 번만 호출)
const cache = new Map();
const TTL = { arrivals: 15 * 1000, busLoc: 15 * 1000, stops: 10 * 60 * 1000, nearby: 10 * 60 * 1000, stopRoutes: 60 * 60 * 1000, routeStops: 60 * 60 * 1000, cities: 60 * 60 * 1000 };

// ---- 방면 계산: 정류장을 지나는 노선의 '같은 방향 마지막 정류장(종점)' 이름을 모음 ----
const dirCache = new Map();
const DIR_TTL = 24 * 60 * 60 * 1000;
async function tagoJson(path, params, rows) {
  const url = new URL(`${BASE}/${path}`);
  url.searchParams.set("serviceKey", TAGO_KEY.value());
  url.searchParams.set("_type", "json");
  url.searchParams.set("numOfRows", String(rows || 100));
  url.searchParams.set("pageNo", "1");
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, String(v));
  const r = await fetch(url);
  return JSON.parse(await r.text());
}
const asItems = j => {
  const it = j && j.response && j.response.body && j.response.body.items && j.response.body.items.item;
  return it == null || it === "" ? [] : Array.isArray(it) ? it : [it];
};
async function routeStopsCached(cityCode, routeId) {
  const key = "rs|" + cityCode + "|" + routeId;
  const hit = dirCache.get(key);
  if (hit && Date.now() - hit.t < DIR_TTL) return hit.v;
  const v = asItems(await tagoJson("BusRouteInfoInqireService/getRouteAcctoThrghSttnList", { cityCode, routeId }, 300))
    .map(x => ({ id: String(x.nodeid), nm: String(x.nodenm), ord: Number(x.nodeord), ud: x.updowncd == null ? "" : String(x.updowncd) }));
  if (v.length) dirCache.set(key, { t: Date.now(), v });
  return v;
}
async function directionOf(cityCode, nodeId) {
  const key = "dir|" + cityCode + "|" + nodeId;
  const hit = dirCache.get(key);
  if (hit && Date.now() - hit.t < DIR_TTL) return hit.v;
  const routes = asItems(await tagoJson("BusSttnInfoInqireService/getSttnThrghRouteList", { cityCode, nodeid: nodeId })).slice(0, 3);
  const counts = {};
  await Promise.all(routes.map(async r => {
    const st = await routeStopsCached(cityCode, r.routeid);
    for (const m of st.filter(x => x.id === nodeId)) {
      const same = st.filter(x => x.ud === m.ud && x.ord >= m.ord);
      if (!same.length) continue;
      const last = same.reduce((a, b) => (b.ord > a.ord ? b : a));
      const name = last.id === nodeId ? "종점" : last.nm;
      counts[name] = (counts[name] || 0) + 1;
    }
  }));
  const names = Object.entries(counts).sort((a, b) => b[1] - a[1]).map(e => e[0]).slice(0, 2);
  if (names.length) dirCache.set(key, { t: Date.now(), v: names });
  return names;
}

exports.bus = onRequest({ secrets: [TAGO_KEY], region: "asia-northeast3", cors: true }, async (req, res) => {
  const opName = String(req.query.op);
  if (opName === "direction") {
    const cityCode = String(req.query.cityCode || ""), nodeId = String(req.query.nodeId || "");
    res.set("Cache-Control", "no-store");
    if (!cityCode || !nodeId) return res.status(400).json({ error: "cityCode, nodeId 필요" });
    try {
      const names = await directionOf(cityCode, nodeId);
      return res.json({ response: { header: { resultCode: "00" }, body: { items: { item: [{ names }] } } } });
    } catch (e) {
      console.error("direction 실패", e);
      return res.json({ response: { header: { resultCode: "99", resultMsg: "방면 정보를 가져오지 못했습니다" } } });
    }
  }
  const op = OPS[opName];
  if (!op) return res.status(400).json({ error: "잘못된 op" });

  const cacheKey = opName + "|" + op.params.map(p => String(req.query[p] || "")).join("|");
  const hit = cache.get(cacheKey);
  if (hit && Date.now() - hit.t < TTL[opName]) {
    res.set("Cache-Control", "no-store");
    return res.type("application/json").send(hit.body);
  }

  const url = new URL(`${BASE}/${op.path}`);
  url.searchParams.set("serviceKey", TAGO_KEY.value());
  url.searchParams.set("_type", "json");
  url.searchParams.set("numOfRows", String(op.rows || 100));
  url.searchParams.set("pageNo", "1");
  for (const p of op.params) if (req.query[p]) url.searchParams.set(p, String(req.query[p]));

  try {
    const r = await fetch(url);
    const text = await r.text();
    if (r.ok && text.includes('"resultCode":"00"')) {
      if (cache.size > 500) cache.clear();
      cache.set(cacheKey, { t: Date.now(), body: text });
    }
    res.set("Cache-Control", "no-store");
    res.type("application/json").send(text);
  } catch (e) {
    res.status(502).json({ error: "공공데이터 서버 호출 실패" });
  }
});
