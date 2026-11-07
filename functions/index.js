// 국토교통부 TAGO 버스 API 프록시 (인증키를 서버에만 보관)
const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");

const VWORLD_KEY = defineSecret("VWORLD_KEY"); // firebase functions:secrets:set VWORLD_KEY (국토부 브이월드 인증키, 정류장 위치의 시·군 확인용)
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
  const counts = {}, nextBy = {};
  await Promise.all(routes.map(async r => {
    const st = await routeStopsCached(cityCode, r.routeid);
    for (const m of st.filter(x => x.id === nodeId)) {
      const same = st.filter(x => x.ud === m.ud && x.ord >= m.ord);
      if (!same.length) continue;
      const last = same.reduce((a, b) => (b.ord > a.ord ? b : a));
      const name = last.id === nodeId ? "종점" : last.nm;
      counts[name] = (counts[name] || 0) + 1;
      const nx = nextStopOf(st, m);
      if (nx && name !== "종점") (nextBy[name] = nextBy[name] || {})[nx.nm] = ((nextBy[name] || {})[nx.nm] || 0) + 1;
    }
  }));
  const names = Object.entries(counts).sort((a, b) => b[1] - a[1]).map(e => e[0]).slice(0, 2);
  const nexts = names.map(n => {                       // 같은 종점이라도 다음 정류장이 갈리면 가장 많은 쪽
    const c = nextBy[n] || {}, k = Object.keys(c).sort((a, b) => c[b] - c[a])[0];
    return k || "";
  });
  const v = { names, nexts };
  if (names.length) dirCache.set(key, { t: Date.now(), v });
  return v;
}
// 같은 방향(updowncd)에서 이 정류장 바로 다음 정류장
function nextStopOf(st, m) {
  let best = null;
  for (const x of st) if (x.ud === m.ud && x.ord > m.ord && (!best || x.ord < best.ord)) best = x;
  return best;
}

// 정류장을 지나는 버스별 방면(이 정류장에서 타면 가는 방향). 이 정류장이 종점인 노선(탈 수 없음)은 뺀다.
async function stopDirsOf(cityCode, nodeId) {
  const key = "sd|" + cityCode + "|" + nodeId;
  const hit = dirCache.get(key);
  if (hit && Date.now() - hit.t < DIR_TTL) return hit.v;
  const all = asItems(await tagoJson("BusSttnInfoInqireService/getSttnThrghRouteList", { cityCode, nodeid: nodeId }));
  const routes = all.slice(0, 25);
  const out = all.slice(25).map(r => ({ routeno: String(r.routeno), dests: [], nexts: [] }));   // 계산 한도를 넘은 노선도 번호는 보여 줌

  let failed = 0;
  await Promise.all(routes.map(async r => {
    let dests = [], nexts = [], onlyTerminal = false;
    try {
      const st = await routeStopsCached(cityCode, r.routeid);
      const ms = st.filter(x => x.id === nodeId);
      let term = 0;
      for (const m of ms) {
        const same = st.filter(x => x.ud === m.ud && x.ord >= m.ord);
        if (!same.length) continue;
        const last = same.reduce((a, b) => (b.ord > a.ord ? b : a));
        if (last.id === nodeId) term++;
        else if (!dests.includes(last.nm)) { dests.push(last.nm); const nx = nextStopOf(st, m); nexts.push(nx ? nx.nm : ""); }
      }
      onlyTerminal = ms.length > 0 && term === ms.length;     // 이 정류장이 해당 노선의 종점일 때
    } catch (e) { failed++; }
    if (onlyTerminal) { dests = ["종점"]; nexts = [""]; }       // 빼지 않고 '종점'으로 표시 (도착정보에는 나오므로 목록에서도 고를 수 있어야 함)
    out.push({ routeno: String(r.routeno), dests, nexts });
  }));
  if (out.length && !failed) dirCache.set(key, { t: Date.now(), v: out });
  return out;
}

// ---- 같은 자리의 다른 시·군 정류장 찾기 (이름이 같고 약 100m 이내) ----
// 괄호 설명은 지우되, 방향을 뜻하는 괄호(건너편·맞은편·방면 등)는 남겨 반대편 정류장과 섞이지 않게 함
const normNm = s => String(s || "").replace(/\([^)]*\)|\[[^\]]*\]/g, m => /건너|맞은|반대|방면|행|상행|하행|내선|외선/.test(m) ? m : "").replace(/[\s·.\-_]/g, "").toLowerCase();
function meters(la1, lo1, la2, lo2) {
  const R = 6371000, r = Math.PI / 180;
  const dLa = (la2 - la1) * r, dLo = (lo2 - lo1) * r;
  const a = Math.sin(dLa / 2) ** 2 + Math.cos(la1 * r) * Math.cos(la2 * r) * Math.sin(dLo / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}
// 이 정류장의 좌표를 이름 검색으로 찾는다
async function stopCoord(cityCode, nodeId, nodeNm) {
  const found = asItems(await tagoJson("BusSttnInfoInqireService/getSttnNoList", { cityCode, nodeNm }, 100))
    .find(x => String(x.nodeid) === String(nodeId));
  if (!found) throw new Error("정류장 좌표를 찾지 못했습니다");
  const la = Number(found.gpslati), lo = Number(found.gpslong);
  if (isNaN(la) || isNaN(lo)) throw new Error("정류장 좌표가 없습니다");
  return { la, lo, nm: found.nodenm };
}
// 좌표가 실제로 속한 시·군을 찾는다 (브이월드 우선, 안 되면 OpenStreetMap. 결과는 오래 보관)
const GEO_TTL = 30 * 24 * 60 * 60 * 1000;
async function locateOf(cityCode, nodeId, nodeNm) {
  const key = "lc|" + cityCode + "|" + nodeId;
  const hit = dirCache.get(key);
  if (hit && Date.now() - hit.t < GEO_TTL) return hit.v;
  const { la, lo } = await stopCoord(cityCode, nodeId, nodeNm);
  const gk = "gc|" + la.toFixed(3) + "|" + lo.toFixed(3);       // 가까운 정류장끼리는 같은 결과를 재사용
  let a = (dirCache.get(gk) || {}).v, src = a ? a._src : "", why = a ? a._why : "";
  if (!a) {                                   // 1) 국토부 브이월드 (국내 행정구역 기준, 더 정확)
    try {
      const k = VWORLD_KEY.value();
      if (k) {
        const u = new URL("https://api.vworld.kr/req/address");
        u.searchParams.set("service", "address"); u.searchParams.set("request", "getAddress"); u.searchParams.set("version", "2.0");
        u.searchParams.set("crs", "epsg:4326"); u.searchParams.set("point", lo + "," + la); u.searchParams.set("format", "json");
        u.searchParams.set("type", "parcel"); u.searchParams.set("key", k); u.searchParams.set("domain", "bustime-c4cfd.web.app");
        const r = await fetch(u);
        const j = await r.json();
        const st = j && j.response && j.response.status === "OK" && j.response.result && j.response.result[0] && j.response.result[0].structure;
        if (st && st.level2) { a = { county: st.level2, city: "", city_district: "", state: st.level1 || "" }; src = "vworld"; }
        else why = "브이월드: " + String((j && j.response && (j.response.status + " " + JSON.stringify(j.response.error || ""))) || "응답 이상").slice(0, 160);
      } else why = "브이월드 키 없음";
    } catch (e) { why = "브이월드 오류: " + e.message; console.error(why); }
  }
  if (!a) {                                   // 2) 못 쓰면 OpenStreetMap
    const u = new URL("https://nominatim.openstreetmap.org/reverse");
    u.searchParams.set("format", "jsonv2"); u.searchParams.set("lat", String(la)); u.searchParams.set("lon", String(lo));
    u.searchParams.set("zoom", "10"); u.searchParams.set("addressdetails", "1"); u.searchParams.set("accept-language", "ko");
    const r = await fetch(u, { headers: { "User-Agent": "bustime-bus-app/1.0 (elder bus arrival helper)" } });
    if (!r.ok) throw new Error("위치 조회 실패 " + r.status);
    const j = await r.json();
    a = j && j.address;
    if (!a) throw new Error("위치 정보 없음");
    src = "osm";
  }
  a._src = src; a._why = why;
  dirCache.set(gk, { t: Date.now(), v: a });
  const v = { county: a.county || "", city: a.city || a.town || a.municipality || "", district: a.city_district || "", state: a.state || a.province || "", src, why };
  dirCache.set(key, { t: Date.now(), v });
  return v;
}
async function twinsOf(cityCode, nodeId, nodeNm) {
  const key = "tw|" + cityCode + "|" + nodeId;
  const hit = dirCache.get(key);
  if (hit && Date.now() - hit.t < DIR_TTL) return hit.v;
  const { la, lo, nm: foundNm } = await stopCoord(cityCode, nodeId, nodeNm);
  const found = { nodenm: foundNm };
  // 2) 주변 정류장 중 다른 시·군 것에서 같은 이름을 고른다
  const me = normNm(nodeNm || found.nodenm);
  const out = [];
  for (const x of asItems(await tagoJson("BusSttnInfoInqireService/getCrdntPrxmtSttnList", { gpsLati: la, gpsLong: lo }, 100))) {
    const cc = x.citycode == null ? "" : String(x.citycode);
    if (!cc || cc === String(cityCode)) continue;
    const d = meters(la, lo, Number(x.gpslati), Number(x.gpslong));
    if (isNaN(d)) continue;
    const nm = normNm(x.nodenm);
    const same = nm === me || (d <= 40 && nm && me && !/[(\[]/.test(String(x.nodenm) + String(nodeNm)) && (nm.includes(me) || me.includes(nm)));
    if (same && d <= 100) out.push({ cityCode: cc, nodeid: String(x.nodeid), nodenm: String(x.nodenm), nodeno: String(x.nodeno == null ? "" : x.nodeno), dist: Math.round(d) });
  }
  out.sort((a, b) => a.dist - b.dist);
  // 시·군마다 가장 가까운 한 곳만 (같은 시·군의 반대편 정류장까지 합치면 방향이 섞임)
  const seen = {}, v = out.filter(x => (seen[x.cityCode] ? false : (seen[x.cityCode] = true))).slice(0, 3);
  dirCache.set(key, { t: Date.now(), v });
  return v;
}

exports.bus = onRequest({ secrets: [TAGO_KEY, VWORLD_KEY], region: "asia-northeast3", cors: true }, async (req, res) => {
  const opName = String(req.query.op);
  if (opName === "direction") {
    const cityCode = String(req.query.cityCode || ""), nodeId = String(req.query.nodeId || "");
    res.set("Cache-Control", "no-store");
    if (!cityCode || !nodeId) return res.status(400).json({ error: "cityCode, nodeId 필요" });
    try {
      const d = await directionOf(cityCode, nodeId);
      return res.json({ response: { header: { resultCode: "00" }, body: { items: { item: [{ names: d.names, nexts: d.nexts }] } } } });
    } catch (e) {
      console.error("direction 실패", e);
      return res.json({ response: { header: { resultCode: "99", resultMsg: "방면 정보를 가져오지 못했습니다" } } });
    }
  }
  if (opName === "locate") {
    const cityCode = String(req.query.cityCode || ""), nodeId = String(req.query.nodeid || ""), nodeNm = String(req.query.nodenm || "");
    res.set("Cache-Control", "no-store");
    if (!cityCode || !nodeId || !nodeNm) return res.status(400).json({ error: "cityCode, nodeid, nodenm 필요" });
    try {
      const v = await locateOf(cityCode, nodeId, nodeNm);
      return res.json({ response: { header: { resultCode: "00" }, body: { items: { item: [v] } } } });
    } catch (e) {
      console.error("locate 실패", e);
      return res.json({ response: { header: { resultCode: "99", resultMsg: "정류장 위치의 시·군을 확인하지 못했습니다" } } });
    }
  }
  if (opName === "twins") {
    const cityCode = String(req.query.cityCode || ""), nodeId = String(req.query.nodeid || ""), nodeNm = String(req.query.nodenm || "");
    res.set("Cache-Control", "no-store");
    if (!cityCode || !nodeId || !nodeNm) return res.status(400).json({ error: "cityCode, nodeid, nodenm 필요" });
    try {
      const items = await twinsOf(cityCode, nodeId, nodeNm);
      return res.json({ response: { header: { resultCode: "00" }, body: { items: items.length ? { item: items } : "" } } });
    } catch (e) {
      console.error("twins 실패", e);
      return res.json({ response: { header: { resultCode: "99", resultMsg: "다른 시·군 정류장을 찾지 못했습니다" } } });
    }
  }
  if (opName === "stopDirs") {
    const cityCode = String(req.query.cityCode || ""), nodeId = String(req.query.nodeid || "");
    res.set("Cache-Control", "no-store");
    if (!cityCode || !nodeId) return res.status(400).json({ error: "cityCode, nodeid 필요" });
    try {
      const items = await stopDirsOf(cityCode, nodeId);
      return res.json({ response: { header: { resultCode: "00" }, body: { items: { item: items } } } });
    } catch (e) {
      console.error("stopDirs 실패", e);
      return res.json({ response: { header: { resultCode: "99", resultMsg: "버스 목록을 가져오지 못했습니다" } } });
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
