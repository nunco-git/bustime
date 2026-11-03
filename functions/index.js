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
  // 좌표(지도 중심) 기준 500m 이내 정류소
  nearby:   { path: "BusSttnInfoInqireService/getCrdntPrxmtSttnList", params: ["gpsLati", "gpsLong"] },
  // 노선별 경유 정류장(순서대로) / 노선별 버스 위치
  routeStops: { path: "BusRouteInfoInqireService/getRouteAcctoThrghSttnList", params: ["cityCode", "routeId"], rows: 300 },
  busLoc:     { path: "BusLcInfoInqireService/getRouteAcctoBusLcList", params: ["cityCode", "routeId"], rows: 100 },
};

// 공공데이터 호출 횟수 절약용 메모리 캐시 (같은 정류장을 여러 명이 봐도 한 번만 호출)
const cache = new Map();
const TTL = { arrivals: 15 * 1000, busLoc: 15 * 1000, stops: 10 * 60 * 1000, nearby: 10 * 60 * 1000, routeStops: 60 * 60 * 1000, cities: 60 * 60 * 1000 };

exports.bus = onRequest({ secrets: [TAGO_KEY], region: "asia-northeast3", cors: true }, async (req, res) => {
  const opName = String(req.query.op);
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
