'use strict';
// SchemaDiff 화면. 상태(S)를 바꾸고 render() 로 전체를 다시 그린다. 이벤트는 data-act 위임으로 받는다.

const LBL = { mod: '변경', from: 'FROM만', to: 'TO만', same: '동일' };
const DBMS = [['oracle', 'Oracle', true], ['postgres', 'PostgreSQL', false], ['mysql', 'MySQL·MariaDB', false]];
const PORT = { oracle: '1521', postgres: '5432', mysql: '3306' };

// saved: 고른 저장된 연결 이름, edited: 고른 뒤 접속 정보를 바꿨는지(바꿨으면 다시 연결 테스트가 필요)
const blank = () => ({ dbms: 'oracle', host: '', port: '1521', mode: 'svc', svc: '', user: '', pw: '', saved: '', edited: false,
  test: 'idle', error: '', version: '', schemas: [], schema: '', save: true, saveName: '' });
const CONN_FIELDS = ['dbms', 'host', 'port', 'mode', 'svc', 'user', 'pw'];
/** 저장된 연결을 그대로 쓰는 중이면 그 이름. */
const savedName = c => c.saved && !c.edited ? c.saved : '';
/** 적용 가능: 스키마가 있고, 연결 테스트에 성공했거나 저장된 연결을 바꾸지 않고 쓰는 중(테스트 실패는 제외). */
const canApply = fm => !!fm.schema.trim() && (fm.test === 'ok' || (!!savedName(fm) && fm.test === 'idle'));

const S = {
  saved: [],
  dr: { open: false, side: 'from', showPw: false },
  form: { from: blank(), to: blank() },
  applied: { from: null, to: null },
  run: null, busy: '', toast: '', hist: null,
  type: 'table', sel: null, tab: 'ddl', q: '', f: { mod: true, from: true, to: true, same: true },
  diff: {},
  listH: 0, // 끌어서 정한 목록 높이(px). 0 이면 CSS 기본값
};

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

async function api(path, body) {
  const r = await fetch('/api/' + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'X-SchemaDiff': '1', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({ error: r.status + ' ' + r.statusText }));
  if (!r.ok) throw new Error(j.error || r.statusText);
  return j;
}

function fail(e) { S.busy = ''; S.toast = e.message || String(e); render(); }

// ---------- 테마 ----------
function initTheme() {
  let t = null;
  try { t = localStorage.getItem('schemadiff.theme'); } catch (e) { /* 저장소를 못 쓰면 기본값 */ }
  document.documentElement.dataset.theme = t === 'light' ? 'light' : 'dark';
}
function toggleTheme() {
  const t = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = t;
  try { localStorage.setItem('schemadiff.theme', t); } catch (e) { /* 무시 */ }
  render();
}

// ---------- 그리기 ----------
// 표시줄에는 저장된 연결 이름(없으면 서비스명)과 스키마만, 전체 접속 정보는 마우스를 올렸을 때 보인다
const connLabel = c => `${savedName(c) || c.svc} · ${c.schema}`;
const connTitle = c => `${savedName(c) ? savedName(c) + '\n' : ''}${c.host}:${c.port}/${c.svc}\n사용자 ${c.user} · 스키마 ${c.schema}`;
const dbmsLabel = c => (DBMS.find(d => d[0] === (c ? c.dbms : 'oracle')) || DBMS[0])[1];

function header() {
  const a = S.applied, ready = a.from && a.to;
  // 연결 전에 이력을 열었으면 그 비교의 대상을 보여준다(초록 점 없음)
  const label = side => a[side] ? connLabel(a[side]) : S.run ? `${S.run[side].name || S.run[side].svc} · ${S.run[side].schema} (이력)` : '연결 안 됨';
  const title = side => a[side] ? connTitle(a[side]) : S.run ? `${S.run[side].label} · ${S.run[side].schema}` : '';
  const conn = side => `<div class="conn" data-act="open" data-side="${side}" title="${esc(title(side))}">
      <span class="lbl ${side}">${side.toUpperCase()}</span><span>${esc(dbmsLabel(a[side]))}</span>
      <span class="sub">${esc(label(side))}</span><span class="dot ${a[side] ? 'ok' : ''}"></span></div>`;
  return `<header class="top">
    <div class="brand"><img src="favicon.svg" alt="">SchemaDiff</div>
    <div class="conns">${conn('from')}<span class="arrow">→</span>${conn('to')}</div>
    <div class="btns">
      <button class="btn" data-act="theme">${document.documentElement.dataset.theme === 'dark' ? '라이트' : '다크'}</button>
      <button class="btn" data-act="history">이력</button>
      <button class="btn" data-act="export" ${S.run ? '' : 'disabled'}>내보내기</button>
      <button class="btn primary" data-act="compare" ${ready ? '' : 'disabled'}>비교 실행</button>
    </div></header>`;
}

function objsOfType() { return S.run ? S.run.objects.filter(o => o.type === S.type) : []; }
function visible() {
  const q = S.q.trim().toLowerCase();
  return objsOfType().filter(o => S.f[o.st] && (!q || o.name.toLowerCase().includes(q) || o.toName.toLowerCase().includes(q)));
}
function current() {
  if (!S.run) return null;
  const list = visible();
  return list.find(o => o.i === S.sel) || list[0] || null;
}

function typebar() {
  const types = S.run ? S.run.types.filter(t => S.run.objects.some(o => o.type === t.id) || !['package', 'type'].includes(t.id)) : [];
  const tabs = types.map(t => {
    const d = S.run.objects.filter(o => o.type === t.id && o.st !== 'same').length;
    return `<div class="type ${t.id === S.type ? 'act' : ''}" data-act="type" data-id="${t.id}">
      <span>${esc(t.label)}</span><span class="badge-n ${d ? 'on' : ''}">${d}</span></div>`;
  }).join('');
  const os = objsOfType();
  const chips = ['mod', 'from', 'to', 'same'].map(k =>
    `<span class="chip ${k} ${S.f[k] ? 'on' : ''}" data-act="chip" data-k="${k}">${LBL[k]} ${os.filter(o => o.st === k).length}</span>`).join('');
  let excl = '';
  if (S.run) {
    const ef = S.run.from.excluded || [], et = S.run.to.excluded || [];
    if (ef.length || et.length)
      excl = `<span class="excl" title="${esc('백업·임시 패턴(data/settings.json 의 exclude)으로 비교에서 뺀 테이블·시퀀스\nFROM: ' + ef.join(', ') + '\nTO: ' + et.join(', '))}">제외 ${ef.length}/${et.length}</span>`;
  }
  return `<div class="typebar"><div class="types">${tabs}</div>
    <div class="filters">${excl}${chips}<input id="q" class="search" value="${esc(S.q)}" placeholder="검색" data-input="q"></div></div>`;
}

function list() {
  const r = S.run;
  const side = s => r ? (r[s].name || r[s].svc) + ' / ' + r[s].schema : '';
  const head = `<div class="grid3 list-head"><div>FROM · ${esc(side('from'))}</div><div>상태</div><div>TO · ${esc(side('to'))}</div></div>`;
  let body;
  if (!r) {
    body = `<div class="empty">상단 FROM / TO 를 눌러 연결을 설정하고 「비교 실행」을 누르세요.<br>FROM 은 기준(예: 운영), TO 는 맞출 대상(예: stage)입니다.</div>`;
  } else {
    const cur = current(), rows = visible();
    body = rows.map(o => `<div class="grid3 row ${cur && o.i === cur.i ? 'act' : ''}" data-act="sel" data-i="${o.i}">
        <div class="side ${o.fromName ? '' : 'none'}"><span>${esc(o.fromName)}</span><span>${esc(o.lSum)}</span></div>
        <div class="st"><span class="badge ${o.st}">${LBL[o.st]}</span></div>
        <div class="side ${o.toName ? '' : 'none'}"><span>${esc(o.toName)}</span><span>${esc(o.rSum)}</span></div></div>`).join('')
      || '<div class="empty">조건에 맞는 오브젝트가 없습니다</div>';
  }
  return `<div class="list"${S.listH ? ` style="height:${S.listH}px"` : ''}>${head}<div class="scroll" id="list-scroll">${body}</div></div>`;
}

function detail() {
  const o = current();
  if (!o) return '<div class="detail"></div>';
  const tabDefs = [['attr', o.type === 'table' ? '컬럼 비교' : '속성 비교'], ['ddl', 'DDL 비교'], ['sql', '동기화 SQL']];
  const tabs = tabDefs.map(([id, label]) => `<div class="tab ${S.tab === id ? 'act' : ''}" data-act="tab" data-id="${id}">${label}</div>`).join('');
  let body = '';
  if (S.tab === 'attr') {
    body = o.attr.map(([k, l, r, st]) => {
      const lb = st === 'mod' ? 'bg-mod' : st === 'from' ? 'bg-from' : st === 'to' ? 'bg-hatch' : '';
      const rb = st === 'mod' ? 'bg-mod' : st === 'to' ? 'bg-to' : st === 'from' ? 'bg-hatch' : '';
      return `<div class="attr-row ${st === 'info' ? 'info' : ''}"><div class="l ${lb}">${esc(l)}</div><div class="k ${st}">${esc(k)}</div><div class="${rb}">${esc(r)}</div></div>`;
    }).join('');
  } else if (S.tab === 'ddl') {
    const rows = S.diff[S.run.id + '|' + o.i];
    if (!rows) {
      loadDiff(o);
      body = '<div class="empty">불러오는 중…</div>';
    } else {
      body = '<div class="ddl">' + rows.map(([k, l, ln, r, rn]) => {
        const lb = k === 'mod' ? 'bg-mod' : k === 'from' ? 'bg-from' : k === 'to' ? 'bg-hatch' : '';
        const rb = k === 'mod' ? 'bg-mod' : k === 'to' ? 'bg-to' : k === 'from' ? 'bg-hatch' : '';
        return `<div class="ddl-row"><span class="n ${lb}">${ln}</span><span class="t ${lb}">${esc(l)}</span><span class="n r ${rb}">${rn}</span><span class="t ${rb}">${esc(r)}</span></div>`;
      }).join('') + '</div>';
    }
  } else {
    body = '<div class="sql">' + o.sql.map((t, i) =>
      `<div class="sql-row"><span class="n">${i + 1}</span><span class="t ${t.startsWith('--') ? 'c' : ''}">${esc(t)}</span></div>`).join('') + '</div>';
  }
  return `<div class="detail"><div class="detail-head">
      <div class="title"><span class="name">${esc(o.name)}</span><span class="badge ${o.st}">${LBL[o.st]}</span><span class="note" title="${esc(o.note)}">${esc(o.note)}</span></div>
      <div class="tabs">${tabs}</div></div>
    <div class="scroll" id="detail-scroll">${body}</div></div>`;
}

function drawer() {
  if (!S.dr.open) return '';
  const side = S.dr.side, other = side === 'from' ? 'to' : 'from', fm = S.form[side];
  const ok = canApply(fm);
  const sideTabs = ['from', 'to'].map(sd => `<div class="side-tab ${sd} ${sd === side ? 'act' : ''}" data-act="side" data-side="${sd}">
      <span class="dot ${S.applied[sd] ? 'ok' : ''}"></span>${sd.toUpperCase()}</div>`).join('');
  const saved = S.saved.map(c => `<div class="card ${fm.saved === c.name ? 'act' : ''}" data-act="pick" data-name="${esc(c.name)}">
      <span class="nm">${esc(c.name)}</span><span class="sb">${esc(dbmsLabel(c) + ' · ' + c.host + '/' + c.svc + ' · ' + (c.schema || c.user))}</span></div>`).join('')
    + `<div class="card new" data-act="new">+ 새 연결</div>`;
  const dbms = DBMS.map(([id, label, on]) => `<div class="${id === fm.dbms ? 'act' : ''} ${on ? '' : 'off'}" ${on ? `data-act="dbms" data-id="${id}"` : `title="아직 지원하지 않습니다"`}>${label}${on ? '' : ' (예정)'}</div>`).join('');
  const isOra = fm.dbms === 'oracle';
  const svcLabel = isOra ? (fm.mode === 'sid' ? 'SID' : 'Service Name') : 'Database';
  const oraTabs = isOra ? `<div class="ora-tabs">${[['svc', 'Service Name'], ['sid', 'SID']].map(([id, l]) =>
    `<span class="${fm.mode === id ? 'act' : ''}" data-act="mode" data-id="${id}">${l}</span>`).join('')}</div>` : '';
  const tm = { idle: '아직 테스트하지 않음', testing: '접속 중…', ok: '연결됨 · ' + fm.version, fail: '연결 실패' }[fm.test];
  const inp = (k, ph, type = 'text') => `<input id="f-${k}" class="inp" type="${type}" value="${esc(fm[k])}" placeholder="${esc(ph)}" data-input="form.${k}">`;
  const fail = fm.test === 'fail' ? `<div class="err"><b>접속할 수 없습니다</b><code>${esc(fm.error)}</code><span>Host·포트·서비스명·사용자·비밀번호를 확인하세요.</span></div>` : '';
  const schemas = fm.test === 'ok' ? `<div class="sec"><span class="cap">조회할 수 있는 스키마 (눌러서 선택)</span><div class="schemas">${fm.schemas.map(n =>
    `<span class="${n === fm.schema.trim().toUpperCase() ? 'act' : ''}" data-act="schema" data-name="${esc(n)}">${esc(n)}</span>`).join('')
    || '<em class="why">조회할 수 있는 스키마가 없습니다. 접속 계정의 권한을 확인하세요.</em>'}</div></div>` : '';
  const applyLabel = S.applied[other] ? '적용' : `적용 후 ${other.toUpperCase()} 설정 →`;
  const why = !fm.schema.trim() ? '스키마를 입력하세요' : ok ? '' : '연결 테스트를 먼저 하세요';
  // 바깥을 눌러도 닫히지 않는다. 입력 중인 연결 정보를 잃지 않도록 취소(또는 ×)로만 닫는다
  return `<div class="veil"></div>
  <div class="drawer">
    <div class="dr-head"><div><div class="t1">${side === 'from' ? 'FROM 연결 (원본)' : 'TO 연결 (대상)'}</div>
      <div class="t2">${side === 'from' ? '비교 기준이 되는 DB' : 'FROM과 비교할 DB · 동기화 SQL이 적용될 쪽'}</div></div>
      <div style="display:flex;align-items:center;gap:12px"><div class="side-tabs">${sideTabs}</div><span class="x" data-act="close">×</span></div></div>
    <div class="dr-body">
      <div class="sec"><span class="cap">저장된 연결</span><div class="saved">${saved}</div></div>
      <div class="hr"></div>
      <div class="form">
        <div class="sec"><span class="cap">DBMS</span><div class="dbms">${dbms}</div></div>
        <div class="f2" style="grid-template-columns:minmax(0,1fr) 110px">
          <label class="fld"><span class="cap">Host</span>${inp('host', 'dev-db.local')}</label>
          <label class="fld"><span class="cap">Port</span>${inp('port', PORT[fm.dbms])}</label></div>
        <div class="fld"><div style="display:flex;align-items:center;justify-content:space-between"><span class="cap">${svcLabel}</span>${oraTabs}</div>${inp('svc', 'DEVPDB')}</div>
        <div class="f2" style="grid-template-columns:minmax(0,1fr) minmax(0,1fr)">
          <label class="fld"><span class="cap">사용자</span>${inp('user', 'APP_OWNER')}</label>
          <label class="fld"><span class="cap">비밀번호</span><div class="pw">${inp('pw', fm.saved ? '저장된 비밀번호 사용' : '', S.dr.showPw ? 'text' : 'password')}
            <span data-act="showpw">${S.dr.showPw ? '숨김' : '보기'}</span></div></label></div>
        <label class="fld"><span class="cap">스키마</span>${inp('schema', 'APP_OWNER')}</label>
        <div class="test"><button data-act="test">${fm.test === 'testing' ? '테스트 중…' : '연결 테스트'}</button>
          <span class="msg ${fm.test}"><span class="dot"></span>${esc(tm)}</span></div>
        ${fail}${schemas}${why ? `<em class="why">적용하려면: ${esc(why)}</em>` : ''}
      </div>
    </div>
    <div class="dr-foot">
      <div class="save"><span class="chk" data-act="savechk"><i class="${fm.save ? 'on' : ''}">${fm.save ? '✓' : ''}</i>연결 저장</span>
        ${fm.save ? `<input id="f-saveName" class="inp" value="${esc(fm.saveName)}" placeholder="이름 (예: dev-oracle)" data-input="form.saveName">` : ''}</div>
      <div class="foot-btns"><button class="cancel" data-act="close">취소</button>
        <button class="apply ${ok ? 'on' : ''}" data-act="apply" title="${esc(why)}">${applyLabel}</button></div>
    </div>
  </div>`;
}

function history() {
  if (!S.hist) return '';
  const rows = S.hist.map(h => {
    const c = h.count || {};
    const cnt = ['mod', 'from', 'to'].map(k => c[k] ? `<span class="badge ${k}">${LBL[k]} ${c[k]}</span>` : '').join('');
    return `<div class="hist-row" data-act="open-hist" data-id="${esc(h.id)}"><span>${esc(h.at)}</span>
      <span class="ft">${esc(h.from.label + ' / ' + h.from.schema)} → ${esc(h.to.label + ' / ' + h.to.schema)}</span><span class="cnt">${cnt}</span></div>`;
  }).join('') || '<div class="empty">아직 비교한 이력이 없습니다</div>';
  return `<div class="veil" data-act="close-hist"></div><div class="modal">
    <div class="dr-head"><div class="t1">이력</div><span class="x" data-act="close-hist">×</span></div>
    <div class="hist">${rows}</div></div>`;
}

function render() {
  // 다시 그려도 포커스·커서·스크롤을 유지한다
  const ae = document.activeElement, focusId = ae && ae.id, caret = ae && ae.selectionStart;
  const scrolls = {};
  document.querySelectorAll('[id$="-scroll"]').forEach(e => { scrolls[e.id] = e.scrollTop; });
  document.getElementById('app').innerHTML = header() + typebar() + list() + '<div class="split" title="끌어서 높이 조절"></div>' + detail() + drawer() + history()
    + (S.busy ? `<div class="busy"><div>${esc(S.busy)}</div></div>` : '')
    + (S.toast ? `<div class="toast" data-act="toast" title="눌러서 닫기">${esc(S.toast)}</div>` : '');
  Object.entries(scrolls).forEach(([id, top]) => { const e = document.getElementById(id); if (e) e.scrollTop = top; });
  if (focusId) {
    const e = document.getElementById(focusId);
    if (e) { e.focus(); if (caret != null && e.setSelectionRange) try { e.setSelectionRange(caret, caret); } catch (x) { /* number 등 */ } }
  }
}

// ---------- 동작 ----------
async function loadDiff(o) {
  const key = S.run.id + '|' + o.i;
  if (S.diff[key] === null) return; // 이미 요청 중
  S.diff[key] = null;
  try {
    S.diff[key] = await api('diff', { run: S.run.id, i: o.i });
  } catch (e) {
    delete S.diff[key];
    return fail(e);
  }
  render();
}

function setRun(res) {
  S.run = res;
  S.diff = {};
  S.sel = null;
  const firstDiff = res.objects.find(o => o.st !== 'same');
  S.type = firstDiff ? firstDiff.type : 'table';
}

async function loadSaved() {
  try { S.saved = await api('connections'); } catch (e) { fail(e); }
}

function openDrawer(side) {
  S.dr = { open: true, side, showPw: false };
  if (S.applied[side]) S.form[side] = { ...S.applied[side] };
  render();
}

async function testConn() {
  const side = S.dr.side, fm = S.form[side];
  fm.test = 'testing';
  render();
  try {
    const r = await api('test', fm);
    Object.assign(fm, { test: 'ok', version: r.version, schemas: r.schemas, error: '' });
    // 이미 적힌 스키마는 그대로 둔다. 비었을 때만 접속 계정의 스키마로 채운다
    if (!fm.schema.trim()) {
      const u = (r.user || fm.user).toUpperCase();
      fm.schema = r.schemas.includes(u) ? u : (r.schemas[0] || '');
    }
  } catch (e) {
    Object.assign(fm, { test: 'fail', error: e.message, schemas: [] });
  }
  render();
}

async function apply() {
  const side = S.dr.side, other = side === 'from' ? 'to' : 'from', fm = S.form[side];
  if (!canApply(fm)) return;
  fm.schema = fm.schema.trim().toUpperCase();
  if (fm.save) {
    const name = fm.saveName.trim() || `${fm.host}/${fm.svc}/${fm.schema}`;
    try {
      S.saved = await api('connections/save', { name, conn: fm });
      Object.assign(fm, { saved: name, edited: false });
    } catch (e) { return fail(e); }
  }
  // 서버는 name 을 비교 결과(이력)에 남겨 화면 표시에 쓴다
  S.applied[side] = { ...fm, name: savedName(fm) };
  if (S.applied[other]) S.dr.open = false;
  else S.dr.side = other;
  render();
}

async function compare() {
  const a = S.applied;
  if (!a.from || !a.to) return;
  S.busy = '비교 중… FROM·TO 메타데이터를 조회하고 있습니다';
  render();
  try {
    setRun(await api('compare', { from: a.from, to: a.to }));
  } catch (e) { return fail(e); }
  S.busy = '';
  render();
}

async function exportSql() {
  if (!S.run) return;
  try {
    const r = await api('export', { run: S.run.id });
    const url = URL.createObjectURL(new Blob([r.sql], { type: 'text/plain;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = r.name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) { fail(e); }
}

const ACT = {
  theme: toggleTheme,
  open: el => openDrawer(el.dataset.side),
  close: () => { S.dr.open = false; render(); },
  side: el => { S.dr.side = el.dataset.side; render(); },
  pick: el => {
    const c = S.saved.find(x => x.name === el.dataset.name);
    S.form[S.dr.side] = { ...blank(), ...c, pw: '', saved: c.name, edited: false, save: false, saveName: c.name,
      schema: c.schema || (c.user || '').toUpperCase() };
    render();
  },
  new: () => { S.form[S.dr.side] = blank(); render(); },
  dbms: el => { const fm = S.form[S.dr.side]; Object.assign(fm, { dbms: el.dataset.id, port: PORT[el.dataset.id], edited: true, test: 'idle', schemas: [] }); render(); },
  mode: el => { const fm = S.form[S.dr.side]; Object.assign(fm, { mode: el.dataset.id, edited: true, test: 'idle', schemas: [] }); render(); },
  showpw: () => { S.dr.showPw = !S.dr.showPw; render(); },
  test: testConn,
  schema: el => { S.form[S.dr.side].schema = el.dataset.name; render(); },
  savechk: () => { const fm = S.form[S.dr.side]; fm.save = !fm.save; render(); },
  apply,
  compare,
  export: exportSql,
  history: async () => { try { S.hist = await api('history'); render(); } catch (e) { fail(e); } },
  'close-hist': () => { S.hist = null; render(); },
  'open-hist': async el => {
    S.hist = null;
    S.busy = '이력을 여는 중…';
    render();
    try { setRun(await api('history/open', { run: el.dataset.id })); } catch (e) { return fail(e); }
    S.busy = '';
    render();
  },
  type: el => { S.type = el.dataset.id; S.sel = null; render(); },
  chip: el => { S.f[el.dataset.k] = !S.f[el.dataset.k]; render(); },
  sel: el => { S.sel = Number(el.dataset.i); render(); },
  tab: el => { S.tab = el.dataset.id; render(); },
  toast: () => { S.toast = ''; render(); },
};

document.addEventListener('click', e => {
  const el = e.target.closest('[data-act]');
  if (!el || el.disabled) return;
  const fn = ACT[el.dataset.act];
  if (fn) fn(el);
});

document.addEventListener('input', e => {
  const k = e.target.dataset.input;
  if (!k) return;
  if (k === 'q') {
    S.q = e.target.value;
  } else {
    const field = k.slice(5), fm = S.form[S.dr.side], old = fm[field];
    fm[field] = e.target.value;
    // 스키마를 따로 정하지 않았으면 사용자명을 따라간다
    if (field === 'user' && (!fm.schema || fm.schema === old.toUpperCase())) fm.schema = e.target.value.toUpperCase();
    // 접속 정보가 바뀌면 테스트를 다시 해야 한다. 저장된 연결 이름은 남긴다(비밀번호를 비워 두면 저장된 비밀번호를 쓴다)
    if (CONN_FIELDS.includes(field)) Object.assign(fm, { edited: true, test: 'idle', schemas: [] });
  }
  render();
});

// 목록과 상세 사이 경계를 끌어 목록 높이를 바꾼다. 끄는 동안은 다시 그리지 않고 높이만 바꾼다
let drag = null;
document.addEventListener('pointerdown', e => {
  if (!e.target.classList.contains('split')) return;
  e.preventDefault();
  drag = { y: e.clientY, h: document.querySelector('.list').offsetHeight };
  document.body.classList.add('resizing');
});
document.addEventListener('pointermove', e => {
  if (!drag) return;
  const list = document.querySelector('.list');
  // 상세 영역이 최소 120px 은 남도록 한다(위쪽 바 56px + 타입 바 46px)
  S.listH = Math.round(Math.max(220, Math.min(window.innerHeight - 222, drag.h + e.clientY - drag.y)));
  list.style.height = S.listH + 'px';
});
document.addEventListener('pointerup', () => {
  if (!drag) return;
  drag = null;
  document.body.classList.remove('resizing');
});

document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && S.hist) { S.hist = null; render(); }
});

initTheme();
loadSaved().then(render);
render();
