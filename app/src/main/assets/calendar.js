//  ============================================================================ 
//    CalDAV Server & HTTP Web Server - Front End GUI
 
//    2026 HUGO
  
//    THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
//    AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
//
// coverage hours can be like: 9-10,2=8:30-1:30,3=8:30-1:30   OR 8-22 
//  ============================================================================ 
const hostname = window.location.hostname || "127.0.0.1";
const CALDAV_URL = window.location.origin + '/calendar/';
const CONFIG_URL = window.location.origin + '/config/';

// Fix for: favicon.ico 401 (Unauthorized) 
if (!document.querySelector("link[rel~='icon']")) {
    let blankFavicon = document.createElement('link');
    blankFavicon.rel = 'icon';
    blankFavicon.href = 'data:,'; 
    document.head.appendChild(blankFavicon);
}

let zoom = 1.0;
let viewMode = 3; 
let currentDate = new Date();
let selectedEventId = null;
let events = [];
let contacts = [];

let isDragging = false, isResizing = false;
let isMonthDragging = false, monthDragEv = null;
window.isEditing = false;
window.isScheduling = false; 

function isEditMode() {
    const chk = document.getElementById('chkEditMode');
    return chk && chk.checked;
}

function validateSchedInput(type, val) {
    if (!val || val.toLowerCase() === 'clear' || val.toLowerCase() === 'default') return true;
    if (['weekly', 'daily', 'rest'].includes(type)) {
        let num = parseFloat(val);
        return !isNaN(num) && num >= 0;
    }
    if (['coverage', 'pref'].includes(type)) {
        let parts = val.split(',');
        for (let p of parts) {
            let pTrim = p.trim();
            if (!pTrim) continue;
            let bounds = pTrim.split('-');
            if (bounds.length !== 2) return false;
            let t1 = parseTimeStr(bounds[0]);
            let t2 = parseTimeStr(bounds[1]);
            if (isNaN(t1) || isNaN(t2)) return false;
        }
        return true;
    }
    if (type === 'days') {
        return /^([1-7](,[1-7])*)?$/.test(val.replace(/\s/g, ''));
    }
    return true;
}

function getEffectiveVisiblePeople() {
    let sel = document.getElementById('comboTempPersonFilter');
    if (sel && sel.value !== "") {
        return [parseInt(sel.value)];
    }
    return visiblePeopleIndices;
}

function populateTempFilter() {
    let sel = document.getElementById('comboTempPersonFilter');
    if (!sel) return;
    let currentVal = sel.value;
    sel.innerHTML = '<option value="">None</option>';
    
    let safeContacts = Array.isArray(contacts) ? contacts : [];
    let validIndices = visiblePeopleIndices.filter(n => typeof n === 'number' && !isNaN(n));

    people.forEach((p, idx) => {
        if (!p) return;
        
        if (hasLoadedSchedUsers && unselectedSchedUsers.has(idx)) return;
        if (validIndices.length > 0 && !validIndices.includes(idx)) return;
        if (validIndices.length === 0 && (!hasLoadedSchedUsers || unselectedSchedUsers.size === 0) && p.startsWith("Person ") && idx >= 7) return; 
        
        let contactMatch = safeContacts.find(c => c.username === p);
        let pDisp = (contactMatch && (contactMatch.fn || contactMatch.fullname)) ? (contactMatch.fn || contactMatch.fullname) : p;
        
        let opt = document.createElement('option');
        opt.value = idx;
        opt.innerText = pDisp;
        sel.appendChild(opt);
    });
    
    if (currentVal && sel.querySelector(`option[value="${currentVal}"]`)) {
        sel.value = currentVal;
    } else {
        sel.value = "";
    }
}

document.getElementById('comboTempPersonFilter')?.addEventListener('change', () => {
    render();
});

let activeCard = null, activeEv = null;
let startMouseY = 0, startMouseX = 0, originalDateStr = "", originalStartMin = 0, originalDuration = 0;
let hasMoved = false;

let currentUser = "";
let canEditAll = false;
let visiblePeopleIndices = [];

let authHeader = sessionStorage.getItem('caldav_auth') || "";
function getAuthHeader() { return authHeader; }

window.addEventListener('keydown', (e) => {
    if (e.key === 'Delete' && selectedEventId && !window.isEditing && document.activeElement.tagName !== 'INPUT' && document.activeElement.tagName !== 'SELECT') {
        document.getElementById('btnDelete')?.click();
    }
});

document.getElementById('btnLogin')?.addEventListener('click', () => {
    let u = document.getElementById('loginUser').value;
    let p = document.getElementById('loginPass').value;
    authHeader = 'Basic ' + btoa(u + ':' + p);
    sessionStorage.setItem('caldav_auth', authHeader);
    document.getElementById('loginModal').style.display = 'none';
    downloadUpdates();
});

document.getElementById('btnLogout')?.addEventListener('click', () => {
    sessionStorage.removeItem('caldav_auth');
    authHeader = "";
    events = []; 
    contacts = [];
    currentUser = "";
    render(); 
    document.getElementById('loginModal').style.display = 'block';
});

function getStorageKey(dateStr) {
    return dateStr.replace(/\//g, '-'); 
}

let people = ["Jack", "Bob", "Charlie", "David", "Eve", "Frank", "Grace"];
for (let i = 7; i < 255; i++) people.push("Person " + (i + 1));
const colors = ["#039BE5", "#33B679", "#8E24AA", "#F4511E", "#E67C73"];

const scrollArea = document.getElementById('scroll-area');
const headerRow = document.getElementById('header-row');
const timelineLabels = document.getElementById('timeline-labels');
const gridBg = document.getElementById('grid-bg');
const eventsLayer = document.getElementById('events-layer');
const lblDateTitle = document.getElementById('lblDateTitle');

function updateLayout() {
    const tb = document.getElementById('toolbar');
    if (tb) tb.style.display = tb.dataset.open === 'true' ? 'flex' : 'none';
}

document.getElementById('btnMenu')?.addEventListener('click', () => {
    const tb = document.getElementById('toolbar');
    if (tb) {
        tb.dataset.open = tb.dataset.open === 'true' ? 'false' : 'true';
        tb.style.display = tb.dataset.open === 'true' ? 'flex' : 'none';
    }
});

function updateDateTitle(text) {
    if (lblDateTitle) lblDateTitle.innerText = text;
    const mobileLbl = document.getElementById('lblDateTitleMobile');
    if (mobileLbl) mobileLbl.innerText = text;
}

function getClientY(e) { 
    if (e.touches && e.touches.length > 0) return e.touches[0].clientY;
    if (e.changedTouches && e.changedTouches.length > 0) return e.changedTouches[0].clientY;
    return e.clientY; 
}
function getClientX(e) { 
    if (e.touches && e.touches.length > 0) return e.touches[0].clientX;
    if (e.changedTouches && e.changedTouches.length > 0) return e.changedTouches[0].clientX;
    return e.clientX; 
}

let lastTouchDblTapTime = 0;

function bindDoubleTap(el, handler) {
    let lastTapTime = 0;
    let lastTapX = 0;
    let lastTapY = 0;
    let startX = 0;
    let startY = 0;
    let moved = false;

    el.addEventListener('touchstart', (e) => {
        if (e.touches && e.touches.length > 1) { moved = true; return; }
        startX = getClientX(e);
        startY = getClientY(e);
        moved = false;
    }, { passive: true });

    el.addEventListener('touchmove', (e) => {
        if (Math.abs(getClientX(e) - startX) > 10 || Math.abs(getClientY(e) - startY) > 10) {
            moved = true;
        }
    }, { passive: true });

    el.addEventListener('touchend', (e) => {
        if (moved) return;
        let now = Date.now();
        let x = getClientX(e);
        let y = getClientY(e);
        if (now - lastTapTime < 350 && Math.abs(x - lastTapX) < 30 && Math.abs(y - lastTapY) < 30) {
            if (e.cancelable) e.preventDefault();
            e.stopPropagation();
            lastTapTime = 0;
            lastTouchDblTapTime = now;
            isDragging = false;
            isResizing = false;
            isMonthDragging = false;
            activeCard = null;
            activeEv = null;
            monthDragEv = null;
            handler(e);
        } else {
            lastTapTime = now;
            lastTapX = x;
            lastTapY = y;
        }
    }, { passive: false });

    el.addEventListener('dblclick', (e) => {
        if (Date.now() - lastTouchDblTapTime < 500) return;
        handler(e);
    });
}

function formatDate(d) { return `${d.getFullYear()}/${String(d.getMonth()+1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`; }
function minToTimeStr(min) {
    let h = Math.floor(min / 60), m = min % 60;
    return `${h > 12 ? h - 12 : (h === 0 ? 12 : h)}:${String(m).padStart(2, '0')} ${h >= 12 ? 'PM' : 'AM'}`;
}

function getColCount() {
    if (viewMode === 1) return 1;
    if (viewMode === 2) return 4;
    if (viewMode === 3) return 7;
    if (viewMode === 4) {
        let offset = window.innerWidth <= 768 ? 40 : 65;
        let availableWidth = (scrollArea ? scrollArea.clientWidth : window.innerWidth) - offset;
        return Math.max(1, Math.min(255, Math.floor(availableWidth / 120)));
    }
    return 7;
}
function isPeopleView() { return viewMode === 4; }
function canEdit(ev) { return canEditAll || ev.personIdx === people.indexOf(currentUser); }

function unrollEvents() {
    let unrolled = [];
    let winStartMs = new Date(currentDate.getFullYear(), currentDate.getMonth() - 2, 1).getTime();
    let winEndMs = new Date(currentDate.getFullYear(), currentDate.getMonth() + 2, 0).getTime();
    if (viewMode === 7) winEndMs = new Date(currentDate.getFullYear() + 1, currentDate.getMonth(), 1).getTime();

    events.forEach(ev => {
        if (!ev.rrule) {
            unrolled.push({ ...ev, realId: ev.id });
            return;
        }
        let rules = {};
        ev.rrule.split(';').forEach(part => {
            let [key, val] = part.split('=');
            if (key && val) rules[key.toUpperCase()] = val;
        });
        let freq = rules['FREQ'];
        let interval = parseInt(rules['INTERVAL'] || '1', 10);
        let count = rules['COUNT'] ? parseInt(rules['COUNT'], 10) : Infinity;
        let untilMs = Infinity;
        if (rules['UNTIL']) {
            let u = rules['UNTIL'];
            let uYear = parseInt(u.substring(0,4), 10);
            let uMon = parseInt(u.substring(4,6), 10) - 1;
            let uDay = parseInt(u.substring(6,8), 10);
            untilMs = new Date(uYear, uMon, uDay, 23, 59, 59).getTime();
        }

        let [y, m, d] = ev.date.split('/').map(Number);
        let currentInst = new Date(y, m - 1, d);
        let instanceCount = 0;

        while (currentInst.getTime() <= winEndMs && currentInst.getTime() <= untilMs && instanceCount < count) {
            if (currentInst.getTime() >= winStartMs) {
                unrolled.push({ ...ev, id: ev.id + "_r" + instanceCount, realId: ev.id, date: formatDate(currentInst) });
            }
            if (freq === 'DAILY') currentInst.setDate(currentInst.getDate() + interval);
            else if (freq === 'WEEKLY') currentInst.setDate(currentInst.getDate() + (7 * interval));
            else if (freq === 'MONTHLY') currentInst.setMonth(currentInst.getMonth() + interval);
            else if (freq === 'YEARLY') currentInst.setFullYear(currentInst.getFullYear() + interval);
            else break;
            
            instanceCount++;
            if (instanceCount > 730) break;
        }
    });
    return unrolled;
}

document.getElementById('btnImportRemote')?.addEventListener('click', () => {
    const modal = document.getElementById('remoteModal');
    if (modal) modal.style.display = 'block';
});

document.getElementById('btnDoImport')?.addEventListener('click', async () => {
    const url = document.getElementById('remUrl').value;
    const port = document.getElementById('remPort').value;
    const user = document.getElementById('remUser').value;
    const pass = document.getElementById('remPass').value;
    try {
        const auth = btoa(`${user}:${pass}`);
        const res = await fetch(`${url}:${port}/calendar/all?t=${Date.now()}`, { headers: { 'Authorization': `Basic ${auth}` } });
        if (res.ok) {
            const importedEvents = await res.json();
            events = events.concat(importedEvents.flat()); 
            await fetch(`${CONFIG_URL}remote`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({url, port, user, pass}) });
            alert("Successfully connected, imported data, and saved to INI.");
            document.getElementById('remoteModal').style.display = 'none';
            render();
        } else { alert("Failed to connect: " + res.statusText); }
    } catch (e) { alert("Connection error: " + e.message); }
});

setInterval(downloadUpdates, 60000);

async function downloadUpdates() {
    if (isDragging || isResizing || isMonthDragging || window.isEditing || window.isScheduling) return; 
    
    if (!getAuthHeader()) {
        const modal = document.getElementById('loginModal');
        if (modal) modal.style.display = 'block';
        return;
    }

    try {
        let headers = { 'Authorization': getAuthHeader() };
        
        const meRes = await fetch(`${window.location.origin}/api/me?t=${Date.now()}`, { headers, cache: 'no-store' });
        if (meRes.status === 401) {
            sessionStorage.removeItem('caldav_auth');
            authHeader = "";
            document.getElementById('loginModal').style.display = 'block';
            return;
        }
        if (meRes.ok) {
            let meData = await meRes.json();
            currentUser = meData.user;
            canEditAll = meData.edit_all;
            if (meData.visible_people && meData.visible_people !== "" && meData.visible_people !== "NaN") {
                visiblePeopleIndices = meData.visible_people.split(',')
                    .map(Number)
                    .filter(n => !isNaN(n));
            } else {
                visiblePeopleIndices = [];
            }
        }

        const pRes = await fetch(`${CALDAV_URL}people.json?t=${Date.now()}`, { headers, cache: 'no-store' });
        if (pRes.ok) {
            try {
                let serverPeople = await pRes.json();
                for(let i = 0; i < serverPeople.length; i++) people[i] = serverPeople[i];
            } catch (e) {}
        }

        const cRes = await fetch(`${window.location.origin}/api/contacts?t=${Date.now()}`, { headers, cache: 'no-store' });
        if (cRes.ok) {
            try { contacts = await cRes.json(); } catch(e) { contacts = []; }
        }
        
        let myIdx = people.indexOf(currentUser);
        if (myIdx === -1 && currentUser && currentUser !== "anonymous") {
            myIdx = people.length;
            people.push(currentUser);
            await putPeopleToCalDAV();
        }

        const response = await fetch(`${CALDAV_URL}all?t=${Date.now()}`, { headers, cache: 'no-store' });
        if (response.ok) {
            try {
                let raw = await response.json();
                if (isDragging || isResizing || isMonthDragging || window.isEditing || window.isScheduling) return; 
                let loadedEvents = Array.isArray(raw) ? raw.flat() : [];
                
                let evMap = new Map();
                loadedEvents.forEach(e => evMap.set(e.id, e));
                events = Array.from(evMap.values());
                
            } catch (err) {
                if (isDragging || isResizing || isMonthDragging || window.isEditing || window.isScheduling) return;
                console.error("Calendar JSON Parse Error - Keeping current local events to prevent wiping the UI due to network glitch.", err);
            }
        }
        
        let dirtyFilter = false;
        let eventPeople = new Set();
        events.forEach(e => eventPeople.add(e.personIdx));
        
        if (visiblePeopleIndices.length === 0) {
            eventPeople.forEach(idx => { if (!visiblePeopleIndices.includes(idx)) visiblePeopleIndices.push(idx); });
            dirtyFilter = true;
        }
        if (myIdx !== -1 && !visiblePeopleIndices.includes(myIdx)) {
            visiblePeopleIndices.push(myIdx);
            dirtyFilter = true;
        }
        
        if (dirtyFilter) saveVisiblePeople();
        populateTempFilter();
        render(); 
    } catch (e) { 
        console.error("Sync Error", e); 
        render();
    }
}

async function saveVisiblePeople() {
    try {
        await fetch(`${window.location.origin}/api/prefs`, { method: 'PUT', headers: { 'Authorization': getAuthHeader() }, body: visiblePeopleIndices.join(',') });
    } catch(e) {}
}

async function putPeopleToCalDAV() {
    try {
        await fetch(`${CALDAV_URL}people.json`, { method: 'PUT', headers: { 'Content-Type': 'application/json', 'Authorization': getAuthHeader() }, body: JSON.stringify(people) });
        populateTempFilter();
    } catch (e) {}
}

async function putEventToCalDAV(ev) {
    let key = getStorageKey(ev.date);
    let dayEvents = events.filter(e => getStorageKey(e.date) === key);
    try {
        await fetch(`${CALDAV_URL}${key}.json`, { method: 'PUT', headers: { 'Content-Type': 'application/json', 'Authorization': getAuthHeader() }, body: JSON.stringify(dayEvents) });
    } catch (e) {}
}

async function deleteEventFromCalDAV(evToDel) {
    let key = getStorageKey(evToDel.date);
    let dayEvents = events.filter(e => getStorageKey(e.date) === key);
    try {
        if (dayEvents.length === 0) {
            await fetch(`${CALDAV_URL}${key}.json`, { method: 'DELETE', headers: { 'Authorization': getAuthHeader() } });
        } else {
            await fetch(`${CALDAV_URL}${key}.json`, { method: 'PUT', headers: { 'Content-Type': 'application/json', 'Authorization': getAuthHeader() }, body: JSON.stringify(dayEvents) });
        }
    } catch (e) {}
}

function handleVCardUpload(e) {
    let file = e.target.files[0];
    if (!file) return;
    let reader = new FileReader();
    reader.onload = function(evt) {
        let content = evt.target.result;
        let lines = content.split(/\r?\n/);
        let currentContact = null;

        for (let line of lines) {
            if (line.trim() === "BEGIN:VCARD") {
                currentContact = { username: "", fn: "", n: "", tel: "", email: "", adr: "", org: "", title: "", url: "", bday: "", note: "" };
            } else if (line.trim() === "END:VCARD") {
                if (currentContact) contacts.push(currentContact);
                currentContact = null;
            } else if (currentContact) {
                let match = line.match(/^([^:]+):(.*)$/);
                if (match) {
                    let key = match[1].split(';')[0].toUpperCase();
                    let val = match[2];
                    if (key === 'FN') currentContact.fn = val;
                    else if (key === 'N') currentContact.n = val;
                    else if (key === 'TEL') currentContact.tel = val;
                    else if (key === 'EMAIL') currentContact.email = val;
                    else if (key === 'ADR') currentContact.adr = val;
                    else if (key === 'ORG') currentContact.org = val;
                    else if (key === 'TITLE') currentContact.title = val;
                    else if (key === 'URL') currentContact.url = val;
                    else if (key === 'BDAY') currentContact.bday = val;
                    else if (key === 'NOTE') currentContact.note = val;
                }
            }
        }
        renderContactsList();
        document.getElementById('btnSaveContacts').click(); 
    };
    reader.readAsText(file);
    e.target.value = ''; 
}

document.getElementById('btnDownloadSingleVCard')?.addEventListener('click', () => {
    let idx = parseInt(document.getElementById('editContactIdx').value);
    if (isNaN(idx) || idx < 0 || idx >= contacts.length) return;
    
    let c = contacts[idx];
    let vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\n";
    let fn = document.getElementById('editContactFn').value || c.fullname || 'Unnamed Contact';
    
    vcf += `FN:${fn}\r\n`;
    if (document.getElementById('editContactN').value) vcf += `N:${document.getElementById('editContactN').value}\r\n`;
    if (document.getElementById('editContactEmail').value) vcf += `EMAIL:${document.getElementById('editContactEmail').value}\r\n`;
    if (document.getElementById('editContactTel').value) vcf += `TEL:${document.getElementById('editContactTel').value}\r\n`;
    if (document.getElementById('editContactAdr').value) vcf += `ADR:${document.getElementById('editContactAdr').value}\r\n`;
    if (document.getElementById('editContactOrg').value) vcf += `ORG:${document.getElementById('editContactOrg').value}\r\n`;
    if (document.getElementById('editContactTitle').value) vcf += `TITLE:${document.getElementById('editContactTitle').value}\r\n`;
    if (document.getElementById('editContactUrl').value) vcf += `URL:${document.getElementById('editContactUrl').value}\r\n`;
    if (document.getElementById('editContactBday').value) vcf += `BDAY:${document.getElementById('editContactBday').value}\r\n`;
    if (document.getElementById('editContactNote').value) vcf += `NOTE:${document.getElementById('editContactNote').value}\r\n`;
    vcf += "END:VCARD\r\n";
    
    let blob = new Blob([vcf], {type: "text/vcard"});
    let urlObj = URL.createObjectURL(blob);
    let a = document.createElement('a');
    a.href = urlObj;
    a.download = fn.replace(/[^a-z0-9]/gi, '_').toLowerCase() + ".vcf";
    a.click();
    URL.revokeObjectURL(urlObj);
});

window.openContactEditor = function(idx) {
    let c = contacts[idx];
    document.getElementById('editContactIdx').value = idx;
    document.getElementById('editContactIdx').dataset.oldUsername = c.username || '';
    
    let dl = document.getElementById('availableUsernames');
    dl.innerHTML = '';
    
    people.forEach(p => {
        if (p && !p.startsWith("Person ")) {
            if (!contacts.some((otherC, otherIdx) => otherIdx !== idx && otherC.username === p)) {
                dl.innerHTML += `<option value="${p}">`;
            }
        }
    });
    
    document.getElementById('editContactUsername').value = c.username || '';
    document.getElementById('editContactFn').value = c.fn || c.fullname || '';
    document.getElementById('editContactN').value = c.n || '';
    document.getElementById('editContactEmail').value = c.email || '';
    document.getElementById('editContactTel').value = c.tel || '';
    document.getElementById('editContactAdr').value = c.adr || '';
    document.getElementById('editContactOrg').value = c.org || '';
    document.getElementById('editContactTitle').value = c.title || '';
    document.getElementById('editContactUrl').value = c.url || '';
    document.getElementById('editContactBday').value = c.bday || '';
    document.getElementById('editContactNote').value = c.note || '';
    
    document.getElementById('contactEditorModal').style.display = 'flex';
};

document.getElementById('btnSaveSingleContact')?.addEventListener('click', async () => {
    let idx = parseInt(document.getElementById('editContactIdx').value);
    if (isNaN(idx) || idx < 0 || idx >= contacts.length) return;
    
    let c = contacts[idx];
    let newUsername = document.getElementById('editContactUsername').value.trim();
    let oldUsername = document.getElementById('editContactIdx').dataset.oldUsername || '';
    
    if (newUsername !== "") {
        if (contacts.some((otherC, otherIdx) => otherIdx !== idx && otherC.username === newUsername)) {
            alert("This username is already linked to another contact. Unique links are required.");
            return;
        }
    }
    
    c.username = newUsername;
    c.fn = document.getElementById('editContactFn').value;
    c.n = document.getElementById('editContactN').value;
    c.email = document.getElementById('editContactEmail').value;
    c.tel = document.getElementById('editContactTel').value;
    c.adr = document.getElementById('editContactAdr').value;
    c.org = document.getElementById('editContactOrg').value;
    c.title = document.getElementById('editContactTitle').value;
    c.url = document.getElementById('editContactUrl').value;
    c.bday = document.getElementById('editContactBday').value;
    c.note = document.getElementById('editContactNote').value;
    delete c.fullname; 
    
    if (newUsername !== oldUsername && newUsername !== "") {
        try {
            await fetch(`${window.location.origin}/api/user`, { method: 'PUT', headers: { 'Authorization': getAuthHeader() }, body: oldUsername ? `${oldUsername}|${newUsername}` : newUsername });
            let pIdx = people.indexOf(oldUsername);
            if (oldUsername && pIdx !== -1) people[pIdx] = newUsername;
            else if (people.indexOf(newUsername) === -1) people.push(newUsername);
            putPeopleToCalDAV();
        } catch(e) {}
    }
    document.getElementById('contactEditorModal').style.display = 'none';
    renderContactsList();
});

function renderContactsList() {
    const list = document.getElementById('contactsList');
    if (!list) return;
    list.innerHTML = '';
    const filterText = (document.getElementById('contactFilter')?.value || '').toLowerCase();
    
    contacts.forEach((c, idx) => {
        let displayFn = c.fn || c.fullname || '';
        if (filterText && !(c.username || '').toLowerCase().includes(filterText) && !displayFn.toLowerCase().includes(filterText)) return;
        
        let div = document.createElement('div');
        div.style.display = 'flex'; div.style.justifyContent = 'space-between'; div.style.alignItems = 'center'; div.style.padding = '8px 12px'; div.style.borderBottom = '1px solid #dadce0';
        
        let linkText = c.username ? ` (Linked: ${c.username})` : '';
        div.innerHTML = `
            <div style="display:flex; flex-direction:column;">
                <b style="font-size:14px; color:#202124;">${displayFn || 'Unnamed Contact'}</b>
                <span style="font-size:11px; color:#5f6368;">${c.email || 'No email'}${linkText}</span>
            </div>
            <div style="display:flex; gap: 6px;">
                <button onclick="openContactEditor(${idx})" style="cursor:pointer; border:1px solid #ccc; border-radius:4px; padding:4px 12px; background:#f8f9fa;">Edit</button>
                <button onclick="contacts.splice(${idx}, 1); renderContactsList();" style="cursor:pointer; color:#d93025; border:1px solid #dadce0; border-radius:4px; padding:4px 12px; background:#fff;">Delete</button>
            </div>
        `;
        list.appendChild(div);
    });
}

document.getElementById('btnContacts')?.addEventListener('click', () => {
    renderContactsList();
    document.getElementById('contactsModal').style.display = 'flex';
});

document.getElementById('btnAddContact')?.addEventListener('click', () => {
    contacts.push({ username: "", fn: "", n: "", tel: "", email: "", adr: "", org: "", title: "", url: "", bday: "", note: "" });
    renderContactsList();
    openContactEditor(contacts.length - 1);
});

document.getElementById('btnSaveContacts')?.addEventListener('click', async () => {
    await fetch(`${window.location.origin}/api/contacts`, { method: 'PUT', headers: { 'Authorization': getAuthHeader(), 'Content-Type': 'application/json' }, body: JSON.stringify(contacts) });
    document.getElementById('contactsModal').style.display = 'none';
    populateTempFilter();
    render();
});

function renderTimeline() {
    if (!gridBg || !timelineLabels || !headerRow) return;
    
    let activeVisiblePeople = getEffectiveVisiblePeople();
    let myIdx = people.indexOf(currentUser);
    if (myIdx !== -1 && !visiblePeopleIndices.includes(myIdx)) visiblePeopleIndices.push(myIdx);

    let cols = getColCount();
    let activeCols = isPeopleView() ? activeVisiblePeople.length : cols;
    if (activeCols === 0) activeCols = 1;
    
    let viewStart = new Date(currentDate);
    if (viewMode === 3) viewStart.setDate(viewStart.getDate() - viewStart.getDay());
    
    updateDateTitle(viewStart.toLocaleDateString(undefined, { month: 'long', day: 'numeric', year: 'numeric' }));
    
    headerRow.innerHTML = '';
    for(let i=0; i<activeCols; i++) {
        let div = document.createElement('div');
        div.className = 'col-header';
        div.style.touchAction = 'manipulation';
        
        if (isPeopleView()) {
            let pIdx = activeVisiblePeople[i];
            let shortName = people[pIdx] || `Person ${pIdx + 1}`;
            let contactMatch = contacts.find(c => c.username === shortName);
            div.innerText = (contactMatch && (contactMatch.fn || contactMatch.fullname)) ? (contactMatch.fn || contactMatch.fullname) : shortName;
            
            bindDoubleTap(div, () => {
                let input = document.createElement('input');
                input.className = 'edit-person-input';
                input.value = people[pIdx] || "";
                div.innerHTML = '';
                div.appendChild(input);
                input.focus();
                
                input.onblur = input.onkeydown = async (ev) => {
                    if (ev.type === 'keydown' && ev.key !== 'Enter') return;
                    input.onblur = null; input.onkeydown = null;
                    let oldName = people[pIdx] || "";
                    let newName = input.value.trim() || `Person ${pIdx + 1}`;
                    while (people.length <= pIdx) people.push(`Person ${people.length + 1}`);
                    people[pIdx] = newName;
                    putPeopleToCalDAV();
                    if (newName !== oldName) {
                        try { await fetch(`${window.location.origin}/api/user`, { method: 'PUT', headers: { 'Authorization': getAuthHeader() }, body: oldName ? `${oldName}|${newName}` : newName }); } catch(e) {}
                    }
                    render();
                };
            });
        } else {
            let d = new Date(viewStart);
            d.setDate(d.getDate() + i);
            div.innerText = d.toLocaleDateString(undefined, { weekday: 'short', month: 'numeric', day: 'numeric' });
        }
        headerRow.appendChild(div);
    }

    const totalHeight = 1440 * zoom;
    timelineLabels.style.height = `${totalHeight}px`;
    gridBg.style.height = `${totalHeight}px`;
    gridBg.style.touchAction = 'manipulation';

    timelineLabels.innerHTML = '';
    for(let i=0; i<=24; i++) {
        let lbl = document.createElement('div');
        lbl.className = 'time-label';
        lbl.style.top = `${i * 60 * zoom}px`;
        if(i > 0 && i < 24) lbl.innerText = minToTimeStr(i * 60);
        timelineLabels.appendChild(lbl);
    }

    gridBg.innerHTML = ''; 
    gridBg.style.background = '';
    gridBg.style.backgroundImage = 'linear-gradient(#e0e0e0 1px, transparent 1px), linear-gradient(90deg, #dadce0 1px, transparent 1px)';
    gridBg.style.backgroundSize = `calc(100% / ${activeCols}) ${60 * zoom}px`;
    
    if (eventsLayer) {
        eventsLayer.innerHTML = '';
        eventsLayer.style.pointerEvents = 'none';
    }

    let displayEvents = typeof unrollEvents === 'function' ? unrollEvents() : events;

    displayEvents.forEach(ev => {
        if (!isPeopleView() && viewMode <= 3) {
            if (myIdx !== -1 && ev.personIdx !== myIdx && activeVisiblePeople.length > 0 && !activeVisiblePeople.includes(ev.personIdx)) return;
        } else if (isPeopleView()) {
            if (activeVisiblePeople.length > 0 && !activeVisiblePeople.includes(ev.personIdx)) return;
        }

        let colIdx = -1;
        if (isPeopleView()) {
            if (ev.date === formatDate(currentDate)) {
                let dispIdx = activeVisiblePeople.indexOf(ev.personIdx);
                if (dispIdx !== -1) colIdx = dispIdx;
            }
        } else {
            let evDate = new Date(ev.date.replace(/\//g, '-') + 'T12:00:00');
            let diffDays = Math.round((evDate.getTime() - new Date(viewStart.getFullYear(), viewStart.getMonth(), viewStart.getDate(), 12, 0, 0).getTime()) / 86400000);
            if (diffDays >= 0 && diffDays < cols) colIdx = diffDays;
        }

        if (colIdx >= 0 && colIdx < activeCols && eventsLayer) {
            let card = document.createElement('div');
            card.className = 'event-card' + (selectedEventId === ev.id ? ' selected' : '');
            card.style.top = `${ev.startMin * zoom}px`;
            card.style.height = `${ev.duration * zoom}px`;
            card.style.left = `${(colIdx / activeCols) * 100}%`;
            card.style.width = `calc(${100 / activeCols}% - 6px)`;
            card.style.marginLeft = '3px';
            card.style.backgroundColor = ev.color;
            card.style.pointerEvents = 'auto';
            card.style.touchAction = 'none';
            card.style.webkitUserSelect = 'none';
            card.style.userSelect = 'none';
            card.style.webkitTouchCallout = 'none';
            card.dataset.id = ev.id;
            
            card.innerHTML = `
                <div class="event-title">${ev.title}</div>
                <div class="event-time">${minToTimeStr(ev.startMin)} - ${minToTimeStr(ev.startMin + ev.duration)}</div>
                <div class="resize-handle" style="touch-action:none;"></div>
            `;
            
            bindDoubleTap(card, (e) => {
                e.stopPropagation(); 
                if (!isEditMode()) return;
                if (!canEdit(ev)) { alert("You do not have permission to edit this event."); return; }
                
                window.isEditing = true;
                isDragging = false;
                
                let titleDiv = card.querySelector('.event-title') || card;
                let input = document.createElement('input');
                input.type = 'text';
                input.value = ev.title;
                input.style.width = '100%'; input.style.color = '#000'; input.style.pointerEvents = 'auto'; 
                input.onmousedown = (ev) => ev.stopPropagation(); input.ontouchstart = (ev) => ev.stopPropagation();
                
                titleDiv.innerHTML = '';
                titleDiv.appendChild(input);
                input.focus();
                
                input.onblur = input.onkeydown = (keyEv) => {
                    if (keyEv.type === 'keydown' && keyEv.key !== 'Enter') return;
                    input.onblur = null; input.onkeydown = null;
                    let realId = ev.id.includes('_r') ? ev.id.split('_r')[0] : ev.id;
                    let targetEv = events.find(x => x.id === realId);
                    if (targetEv) {
                        targetEv.title = input.value || "Untitled Event";
                        putEventToCalDAV(targetEv);
                    }
                    window.isEditing = false;
                    render();
                };
            });

            card.addEventListener('mousedown', handleDrag);
            card.addEventListener('touchstart', handleDrag, {passive: false});
            card.querySelector('.resize-handle').addEventListener('mousedown', handleResize);
            card.querySelector('.resize-handle').addEventListener('touchstart', handleResize, {passive: false});
            eventsLayer.appendChild(card);
        }
    });
}

function renderMonth() {
    if (!gridBg || !headerRow || !timelineLabels) return;
    let activeVisiblePeople = getEffectiveVisiblePeople();
    
    updateDateTitle(currentDate.toLocaleDateString(undefined, { month: 'long', year: 'numeric' }));
    headerRow.innerHTML = '';
    const days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    days.forEach(d => {
        let div = document.createElement('div');
        div.className = 'col-header'; div.style.flex = '1'; div.style.textAlign = 'center'; div.innerText = d;
        headerRow.appendChild(div);
    });

    timelineLabels.innerHTML = '';
    gridBg.style.background = 'none'; gridBg.innerHTML = '';
    if (eventsLayer) eventsLayer.innerHTML = '';
    
    let grid = document.createElement('div'); grid.className = 'month-grid';
    
    let y = currentDate.getFullYear();
    let m = currentDate.getMonth();
    let firstDay = new Date(y, m, 1).getDay();
    let daysInMonth = new Date(y, m + 1, 0).getDate();
    
    let cellCount = 0;
    let displayEvents = typeof unrollEvents === 'function' ? unrollEvents() : events;
    let myIdx = people.indexOf(currentUser);

    for (let row = 0; row < 6; row++) {
        for (let col = 0; col < 7; col++) {
            let cell = document.createElement('div'); cell.className = 'month-cell';
            cell.style.touchAction = 'manipulation';
            let dayNum = cellCount - firstDay + 1;
            let cellDateStr, displayNum, isOtherMonth = false;

            if (dayNum <= 0) {
                displayNum = new Date(y, m, 0).getDate() + dayNum;
                cellDateStr = formatDate(new Date(y, m - 1, displayNum));
                isOtherMonth = true;
            } else if (dayNum > daysInMonth) {
                displayNum = dayNum - daysInMonth;
                cellDateStr = formatDate(new Date(y, m + 1, displayNum));
                isOtherMonth = true;
            } else {
                displayNum = dayNum;
                cellDateStr = `${y}/${String(m+1).padStart(2,'0')}/${String(dayNum).padStart(2,'0')}`;
            }

            cell.innerHTML = `<div class="month-day-num" style="${isOtherMonth ? 'color: #9aa0a6;' : ''}">${displayNum}</div>`;
            cell.dataset.date = cellDateStr;
            cell.dataset.other = isOtherMonth ? 'true' : 'false';
            
            let validEvents = displayEvents.filter(e => {
                if (e.date !== cellDateStr) return false;
                if (myIdx !== -1 && e.personIdx === myIdx) return true;
                if (activeVisiblePeople.length > 0 && !activeVisiblePeople.includes(e.personIdx)) return false;
                return true;
            });
            
            validEvents.forEach(ev => {
                let shortName = people[ev.personIdx] || "";
                let contactMatch = contacts.find(c => c.username === shortName);
                let pDisp = (contactMatch && (contactMatch.fn || contactMatch.fullname)) ? (contactMatch.fn || contactMatch.fullname) : shortName;
                
                let evDiv = document.createElement('div');
                evDiv.className = 'month-event';
                evDiv.style.backgroundColor = ev.color;
                evDiv.style.touchAction = 'none';
                evDiv.style.webkitUserSelect = 'none';
                evDiv.style.userSelect = 'none';
                evDiv.style.webkitTouchCallout = 'none';
                evDiv.innerText = ev.title + (pDisp ? " - " + pDisp : "");
                evDiv.dataset.id = ev.id;
                if(selectedEventId === ev.id) evDiv.style.outline = "2px solid #000";
                
                evDiv.onclick = (e) => { 
                    e.stopPropagation(); selectedEventId = ev.id; 
                    document.querySelectorAll('.month-event').forEach(el => el.style.outline = 'none');
                    evDiv.style.outline = "2px solid #000"; 
                };
                
                evDiv.addEventListener('mousedown', startMonthDrag);
                evDiv.addEventListener('touchstart', startMonthDrag, {passive: false});
                bindDoubleTap(evDiv, (e) => {
                    e.stopPropagation();
                    if (!isEditMode()) return;
                    if (!canEdit(ev)) { alert("You do not have permission to edit this event."); return; }
                    window.isEditing = true;
                    
                    let input = document.createElement('input'); input.type = 'text'; input.value = ev.title;
                    input.style.width = '100%'; input.style.color = '#000'; input.style.pointerEvents = 'auto'; 
                    input.onmousedown = (ev) => ev.stopPropagation(); input.ontouchstart = (ev) => ev.stopPropagation();
                    
                    evDiv.innerHTML = ''; evDiv.appendChild(input); input.focus();
                    
                    input.onblur = input.onkeydown = (keyEv) => {
                        if (keyEv.type === 'keydown' && keyEv.key !== 'Enter') return;
                        input.onblur = null; input.onkeydown = null;
                        
                        let realId = ev.id.includes('_r') ? ev.id.split('_r')[0] : ev.id;
                        let targetEv = events.find(x => x.id === realId);
                        if (targetEv) {
                            targetEv.title = input.value || "Untitled Event";
                            putEventToCalDAV(targetEv);
                        }
                        window.isEditing = false;
                        render();
                    };
                });
                cell.appendChild(evDiv);
            });
            
            bindDoubleTap(cell, () => {
                if (!isEditMode()) return;
                let targetIdx = people.indexOf(currentUser);
                if (targetIdx === -1 && currentUser) {
                    targetIdx = people.length; people.push(currentUser); putPeopleToCalDAV();
                } else if (targetIdx === -1) { targetIdx = 0; }
                
                let newEv = { 
                    id: Date.now().toString(), title: document.getElementById('comboDefaultEvent')?.value || "WORKING", 
                    startMin: 540, duration: 60, color: colors[targetIdx % colors.length], date: cellDateStr, personIdx: targetIdx 
                };
                events.push(newEv); putEventToCalDAV(newEv); render();
            });
            
            if (isOtherMonth) cell.style.backgroundColor = '#f1f3f4';
            grid.appendChild(cell);
            cellCount++;
        }
    }
    gridBg.appendChild(grid);
}

function startMonthDrag(e) {
    if (!isEditMode()) return;
    e.stopPropagation();
    if (window.isEditing) return;
    selectedEventId = this.dataset.id;
    let realId = selectedEventId.includes('_r') ? selectedEventId.split('_r')[0] : selectedEventId;
    monthDragEv = events.find(x => x.id === realId);
    
    if (!monthDragEv || !canEdit(monthDragEv)) { monthDragEv = null; return; }
    if (e.type === 'touchstart' && e.cancelable) e.preventDefault();
    isMonthDragging = true; hasMoved = false;
    document.querySelectorAll('.month-event').forEach(el => el.style.outline = 'none');
    this.style.outline = "2px solid #000";
}

function renderUpcoming() {
    if (!gridBg || !headerRow || !timelineLabels) return;
    updateDateTitle("Events");
    let activeVisiblePeople = getEffectiveVisiblePeople();
    headerRow.innerHTML = ''; timelineLabels.innerHTML = '';
    gridBg.style.background = 'none'; gridBg.innerHTML = '';
    if (eventsLayer) eventsLayer.innerHTML = '';

    let container = document.createElement('div'); container.className = 'upcoming-list';
    
    let displayEvents = typeof unrollEvents === 'function' ? unrollEvents() : events;
    let myIdx = people.indexOf(currentUser);
    
    let validEvents = displayEvents.filter(e => {
        if (e.date < formatDate(new Date())) return false;
        if (activeVisiblePeople.length > 0 && !activeVisiblePeople.includes(e.personIdx)) return false;
        return true;
    });
    
    validEvents.sort((a,b) => a.date.localeCompare(b.date) || a.startMin - b.startMin).forEach(ev => {
        let card = document.createElement('div'); card.className = 'upcoming-card';
        let shortName = people[ev.personIdx] || "";
        let contactMatch = contacts.find(c => c.username === shortName);
        let p = (contactMatch && (contactMatch.fn || contactMatch.fullname)) ? (contactMatch.fn || contactMatch.fullname) : shortName;
        card.innerHTML = `
            <div class="upcoming-color" style="background:${ev.color}"></div>
            <div class="upcoming-details">
                <h3>${ev.title} - ${p}</h3>
                <p>${ev.date} &nbsp;|&nbsp; ${minToTimeStr(ev.startMin)} - ${minToTimeStr(ev.startMin + ev.duration)}</p>
            </div>
        `;
        container.appendChild(card);
    });
    gridBg.appendChild(container);
}

function renderScheduleTable() {
    if (!gridBg || !headerRow || !timelineLabels) return;
    
    let viewStart = new Date(currentDate);
    viewStart.setDate(viewStart.getDate() - viewStart.getDay()); 

    updateDateTitle("Schedule");
    headerRow.innerHTML = '';
    timelineLabels.innerHTML = '';
    gridBg.style.background = 'none';
    gridBg.innerHTML = '';
    if (eventsLayer) eventsLayer.innerHTML = '';

    let table = document.createElement('table');
    table.style.width = '100%';
    table.style.borderCollapse = 'collapse';
    table.style.fontSize = '12px';
    table.style.textAlign = 'left';
    table.style.userSelect = 'text';
    table.style.webkitUserSelect = 'text';
    table.style.tableLayout = 'fixed';

    let thead = document.createElement('thead');
    let hr = document.createElement('tr');
    hr.style.borderBottom = '2px solid #dadce0';
    hr.style.backgroundColor = '#f8f9fa';
    
    let th = document.createElement('th');
    th.innerText = 'Worker';
    th.style.padding = '6px';
    th.style.width = '12%';
    th.style.userSelect = 'text';
    hr.appendChild(th);

    let thHours = document.createElement('th');
    thHours.innerText = 'Hours';
    thHours.style.padding = '6px';
    thHours.style.width = '6%';
    thHours.style.userSelect = 'text';
    hr.appendChild(thHours);

    for (let i = 0; i < 7; i++) {
        let d = new Date(viewStart);
        d.setDate(d.getDate() + i);
        let thd = document.createElement('th');
        thd.innerText = d.toLocaleDateString(undefined, { weekday: 'short', month: 'numeric', day: 'numeric' });
        thd.style.padding = '6px';
        thd.style.borderLeft = '1px solid #dadce0';
        thd.style.userSelect = 'text';
        hr.appendChild(thd);
    }
    thead.appendChild(hr);
    table.appendChild(thead);

    let tbody = document.createElement('tbody');
    let activeVisiblePeople = getEffectiveVisiblePeople();
    let displayEvents = typeof unrollEvents === 'function' ? unrollEvents() : events;
    
    let gRestStr = document.getElementById('globalSchedRest')?.value;
    let gRestMins = (gRestStr && gRestStr.toLowerCase() !== 'clear') ? parseFloat(gRestStr) * 60 : 0;
    if (isNaN(gRestMins)) gRestMins = 0;
    
    activeVisiblePeople.forEach(pIdx => {
        let tr = document.createElement('tr');
        tr.style.borderBottom = '1px solid #e0e0e0';
        
        let tdName = document.createElement('td');
        tdName.style.padding = '6px';
        tdName.style.userSelect = 'text';
        tdName.style.fontWeight = 'bold';
        tdName.style.color = '#3c4043';
        let shortName = people[pIdx] || `Person ${pIdx + 1}`;
        let contactMatch = contacts.find(c => c.username === shortName);
        tdName.innerText = (contactMatch && (contactMatch.fn || contactMatch.fullname)) ? (contactMatch.fn || contactMatch.fullname) : shortName;
        tr.appendChild(tdName);

        let uRestMins = gRestMins;
        if (schedUserData[pIdx] && schedUserData[pIdx].r && schedUserData[pIdx].r.toLowerCase() !== 'clear') {
            uRestMins = parseFloat(schedUserData[pIdx].r) * 60;
        }

        let totalPaidMins = 0;
        for (let i = 0; i < 7; i++) {
            let d = new Date(viewStart);
            d.setDate(d.getDate() + i);
            let dayEvs = displayEvents.filter(e => e.date === formatDate(d) && e.personIdx === pIdx);
            
            let dailyWorkMins = 0;
            dayEvs.forEach(e => {
                if (!/\bOFF\b/.test((e.title || "").toUpperCase())) {
                    dailyWorkMins += e.duration;
                }
            });
            
            if (dailyWorkMins > uRestMins) {
                totalPaidMins += (dailyWorkMins - uRestMins);
            } else {
                totalPaidMins += dailyWorkMins;
            }
        }
        
        let tdHours = document.createElement('td');
        tdHours.style.padding = '6px';
        tdHours.style.userSelect = 'text';
        tdHours.style.fontWeight = 'bold';
        tdHours.style.color = '#3c4043';
        tdHours.innerText = (totalPaidMins / 60).toFixed(1) + 'h';
        tr.appendChild(tdHours);

        for (let i = 0; i < 7; i++) {
            let d = new Date(viewStart);
            d.setDate(d.getDate() + i);
            let dStr = formatDate(d);
            
            let tdDay = document.createElement('td');
            tdDay.style.padding = '4px';
            tdDay.style.borderLeft = '1px solid #e0e0e0';
            tdDay.style.verticalAlign = 'top';
            tdDay.style.userSelect = 'text';
            
            let dayEvs = displayEvents.filter(e => e.date === dStr && e.personIdx === pIdx);
            dayEvs.sort((a,b) => a.startMin - b.startMin);
            
            let html = dayEvs.map(e => {
                let isOff = (e.title || "").toUpperCase().includes("OFF");
                
                let hrs = e.duration / 60;
                let hrsStr = Number.isInteger(hrs) ? hrs : hrs.toFixed(1);
                let displayTitle = `${e.title} - ${hrsStr}h`;
                
                let timeStr = `${minToTimeStr(e.startMin)} - ${minToTimeStr(e.startMin + e.duration)}`;
                let content = isOff ? `<span style="font-weight:bold; user-select:text;">${displayTitle}</span>` : `<span style="font-weight:bold; user-select:text;">${displayTitle}</span><br><span style="user-select:text;">${timeStr}</span>`;
                return `<div style="background-color:${e.color}; color:#fff; border-radius:4px; padding:4px 6px; margin-bottom:4px; font-size:11px; line-height:1.3; box-shadow:0 1px 2px rgba(0,0,0,0.15); user-select:text; -webkit-user-select:text;">${content}</div>`;
            }).join('');
            
            tdDay.innerHTML = html;
            tr.appendChild(tdDay);
        }
        tbody.appendChild(tr);
    });
    
    table.appendChild(tbody);
    let container = document.createElement('div');
    container.style.padding = '10px';
    container.style.overflowY = 'auto';
    container.style.height = '100%';
    container.style.width = '100%';
    container.style.userSelect = 'text';
    container.style.webkitUserSelect = 'text';
    container.appendChild(table);
    
    gridBg.appendChild(container);
}

function render() {
    if (viewMode === 6) renderMonth();
    else if (viewMode === 7) renderUpcoming();
    else if (viewMode === 8) renderScheduleTable();
    else renderTimeline();
}

window.addEventListener('resize', () => { if (!window.isEditing) { render(); updateLayout(); } });

function handleDrag(e) {
    if (!isEditMode()) return;
    if (e.target.classList.contains('resize-handle') || e.target.tagName === 'INPUT') return;
    if (window.isEditing) return;
    e.stopPropagation();
    
    selectedEventId = e.currentTarget.dataset.id;
    let realId = selectedEventId.includes('_r') ? selectedEventId.split('_r')[0] : selectedEventId;
    activeEv = events.find(ev => ev.id === realId);
    if (!activeEv || !canEdit(activeEv)) { activeEv = null; return; }
    if (e.type === 'touchstart' && e.cancelable) e.preventDefault();
    
    originalDateStr = activeEv.date;
    
    if (e.ctrlKey || e.metaKey) {
        let clonedEv = { ...activeEv, id: Date.now().toString() };
        events.push(clonedEv);
        putEventToCalDAV(clonedEv);
        activeEv = clonedEv; 
        selectedEventId = clonedEv.id; 
        render(); 
    } else {
        document.querySelectorAll('.event-card').forEach(c => c.classList.remove('selected'));
        e.currentTarget.classList.add('selected');
    }

    originalStartMin = activeEv.startMin;
    startMouseY = getClientY(e); 
    startMouseX = getClientX(e);
    isDragging = true; 
    hasMoved = false;
    activeCard = document.querySelector(`.event-card[data-id='${selectedEventId}']`);
}

function handleResize(e) {
    if (!isEditMode()) return;
    e.stopPropagation();
    if (window.isEditing) return;
    activeCard = e.target.parentElement;
    selectedEventId = activeCard.dataset.id;
    let realId = selectedEventId.includes('_r') ? selectedEventId.split('_r')[0] : selectedEventId;
    activeEv = events.find(ev => ev.id === realId);
    
    if (!activeEv || !canEdit(activeEv)) { activeEv = null; return; }
    if (e.type === 'touchstart' && e.cancelable) e.preventDefault();
    isResizing = true; hasMoved = true;
    startMouseY = getClientY(e); originalDuration = activeEv.duration;
}

function handleMove(e) {
    if (isMonthDragging && monthDragEv) {
        if (e.cancelable) e.preventDefault();
        hasMoved = true;
        let target = document.elementFromPoint(getClientX(e), getClientY(e));
        document.querySelectorAll('.month-cell').forEach(c => c.style.backgroundColor = c.dataset.other === 'true' ? '#f1f3f4' : '');
        if (target && target.closest('.month-cell')) target.closest('.month-cell').style.backgroundColor = '#e8eaed';
        return;
    }

    if (!activeCard || !activeEv) return;
    if (isDragging || isResizing) { if (e.cancelable) e.preventDefault(); }
    
    if (isDragging) {
        let deltaY = getClientY(e) - startMouseY;
        let deltaX = getClientX(e) - startMouseX;
        
        if (!hasMoved && Math.abs(deltaY) < 5 && Math.abs(deltaX) < 5) return;
        hasMoved = true;
        let activeVisiblePeople = getEffectiveVisiblePeople();

        let deltaMins = Math.round(deltaY / zoom / 15) * 15; 
        let newMin = Math.max(0, Math.min(1440 - activeEv.duration, originalStartMin + deltaMins));
        activeEv.startMin = newMin;
        activeCard.style.top = `${newMin * zoom}px`;
        activeCard.querySelector('.event-time').innerText = `${minToTimeStr(newMin)} - ${minToTimeStr(newMin + activeEv.duration)}`;

        let rect = gridBg.getBoundingClientRect();
        let cols = getColCount();
        let activeCols = isPeopleView() ? activeVisiblePeople.length : cols;
        if (activeCols === 0) activeCols = 1;
        
        let xOffset = getClientX(e) - rect.left;
        let newColIdx = Math.max(0, Math.min(activeCols - 1, Math.floor(xOffset / (rect.width / activeCols))));
        
        if (isPeopleView()) {
            let tgtIdx = activeVisiblePeople[newColIdx];
            if (!canEditAll && tgtIdx !== people.indexOf(currentUser)) {} else {
                activeEv.personIdx = tgtIdx;
                activeEv.color = colors[tgtIdx % colors.length];
            }
        } else {
            let dt = new Date(currentDate.getFullYear(), currentDate.getMonth(), currentDate.getDate()); 
            if (viewMode === 3) dt.setDate(dt.getDate() - dt.getDay());
            dt.setDate(dt.getDate() + newColIdx);
            activeEv.date = formatDate(dt);
        }
        
        let displayCol = isPeopleView() ? activeVisiblePeople.indexOf(activeEv.personIdx) : newColIdx;
        activeCard.style.left = `${(displayCol / activeCols) * 100}%`;
        activeCard.style.backgroundColor = activeEv.color;
        
    } else if (isResizing) {
        let deltaY = getClientY(e) - startMouseY;
        let deltaMins = Math.round(deltaY / zoom / 15) * 15;
        let newDur = Math.max(15, Math.min(1440 - activeEv.startMin, originalDuration + deltaMins));
        activeEv.duration = newDur;
        activeCard.style.height = `${newDur * zoom}px`;
        activeCard.querySelector('.event-time').innerText = `${minToTimeStr(activeEv.startMin)} - ${minToTimeStr(activeEv.startMin + newDur)}`;
    }
}

function handleUp(e) {
    if (isMonthDragging && monthDragEv) {
        if (hasMoved) {
            let target = document.elementFromPoint(getClientX(e), getClientY(e));
            let cell = target ? target.closest('.month-cell') : null;
            if (cell && cell.dataset.date && monthDragEv.date !== cell.dataset.date) {
                let oldDateStr = monthDragEv.date;
                monthDragEv.date = cell.dataset.date;
                
                if (getStorageKey(oldDateStr) !== getStorageKey(monthDragEv.date)) {
                    let oldKey = getStorageKey(oldDateStr);
                    let oldEvents = events.filter(ev => getStorageKey(ev.date) === oldKey);
                    if (oldEvents.length === 0) fetch(`${CALDAV_URL}${oldKey}.json`, { method: 'DELETE', headers: { 'Authorization': getAuthHeader() } });
                    else fetch(`${CALDAV_URL}${oldKey}.json`, { method: 'PUT', headers: { 'Content-Type': 'application/json', 'Authorization': getAuthHeader() }, body: JSON.stringify(oldEvents) });
                }
                putEventToCalDAV(monthDragEv);
                render();
            }
        }
        isMonthDragging = false; monthDragEv = null;
        document.querySelectorAll('.month-cell').forEach(c => c.style.backgroundColor = c.dataset.other === 'true' ? '#f1f3f4' : '');
        return;
    }

    if ((isDragging || isResizing) && activeEv && hasMoved) {
        if (originalDateStr && getStorageKey(originalDateStr) !== getStorageKey(activeEv.date)) {
            let oldKey = getStorageKey(originalDateStr);
            let oldEvents = events.filter(ev => getStorageKey(ev.date) === oldKey);
            if (oldEvents.length === 0) fetch(`${CALDAV_URL}${oldKey}.json`, { method: 'DELETE', headers: { 'Authorization': getAuthHeader() } });
            else fetch(`${CALDAV_URL}${oldKey}.json`, { method: 'PUT', headers: { 'Content-Type': 'application/json', 'Authorization': getAuthHeader() }, body: JSON.stringify(oldEvents) });
        }
        putEventToCalDAV(activeEv);
    }
    isDragging = false; isResizing = false; activeCard = null; activeEv = null;
}

window.addEventListener('mousemove', handleMove, {passive: false});
window.addEventListener('touchmove', handleMove, {passive: false});
window.addEventListener('mouseup', handleUp);
window.addEventListener('touchend', handleUp);

if (gridBg) {
    bindDoubleTap(gridBg, e => {
        if (viewMode >= 6) return; 
        if (!isEditMode()) return;
        
        let rect = gridBg.getBoundingClientRect();
        let y = getClientY(e) - rect.top; let x = getClientX(e) - rect.left;
        let activeVisiblePeople = getEffectiveVisiblePeople();
        
        let stMin = Math.max(0, Math.min(1440 - 60, Math.floor(y / zoom / 30) * 30));
        let cols = getColCount();
        let activeCols = isPeopleView() ? activeVisiblePeople.length : cols;
        if (activeCols === 0) activeCols = 1;
        
        let colIdx = Math.max(0, Math.min(activeCols - 1, Math.floor(x / (rect.width / activeCols))));
        let dt = new Date(currentDate.getFullYear(), currentDate.getMonth(), currentDate.getDate());
        if (viewMode === 3) dt.setDate(dt.getDate() - dt.getDay());
        if (!isPeopleView()) dt.setDate(dt.getDate() + colIdx);
        
        let pIdx = isPeopleView() ? activeVisiblePeople[colIdx] : people.indexOf(currentUser);
        if (pIdx === -1 || pIdx === undefined) {
            pIdx = people.indexOf(currentUser);
            if (pIdx === -1 && currentUser) { pIdx = people.length; people.push(currentUser); putPeopleToCalDAV(); }
            else if (pIdx === -1) pIdx = 0;
        }
        
        if (!canEditAll && pIdx !== people.indexOf(currentUser)) { alert("You can only create events on your own schedule."); return; }
        
        let newEv = {
            id: Date.now().toString(), title: document.getElementById('comboDefaultEvent')?.value || "WORKING", startMin: stMin, duration: 60,
            color: colors[pIdx % colors.length], date: formatDate(dt), personIdx: pIdx
        };
        events.push(newEv);
        putEventToCalDAV(newEv);
        render();
    });
}

document.getElementById('comboView')?.addEventListener('change', e => { 
    viewMode = parseInt(e.target.value); 
    document.getElementById('btnFilterPeople').style.display = 'inline-block';
    document.getElementById('btnCloneForUsers').style.display = 'inline-block';
    render(); 
});
document.getElementById('btnPrev')?.addEventListener('click', () => { 
    if (viewMode === 6) currentDate.setMonth(currentDate.getMonth() - 1);
    else currentDate.setDate(currentDate.getDate() - (viewMode === 1 ? 1 : (viewMode === 2 ? 4 : 7))); 
    render(); 
});
document.getElementById('btnNext')?.addEventListener('click', () => { 
    if (viewMode === 6) currentDate.setMonth(currentDate.getMonth() + 1);
    else currentDate.setDate(currentDate.getDate() + (viewMode === 1 ? 1 : (viewMode === 2 ? 4 : 7))); 
    render(); 
});
document.getElementById('btnPrevDay')?.addEventListener('click', () => { 
    currentDate.setDate(currentDate.getDate() - 1); 
    render(); 
});
document.getElementById('btnNextDay')?.addEventListener('click', () => { 
    currentDate.setDate(currentDate.getDate() + 1); 
    render(); 
});
document.getElementById('btnZoomIn')?.addEventListener('click', () => { zoom = Math.min(2.5, zoom + 0.2); render(); });
document.getElementById('btnZoomOut')?.addEventListener('click', () => { zoom = Math.max(0.6, zoom - 0.2); render(); });
document.getElementById('btnSync')?.addEventListener('click', downloadUpdates);


document.getElementById('btnCopy')?.addEventListener('click', () => {
    if (!isEditMode()) { alert("Please enable Edit Mode to duplicate events."); return; }
    if (selectedEventId) {
        let realId = selectedEventId.includes('_r') ? selectedEventId.split('_r')[0] : selectedEventId;
        let ev = events.find(e => e.id === realId);
        if (ev && canEdit(ev)) {
            let newEv = { ...ev, id: Date.now().toString(), startMin: ev.startMin };
            events.push(newEv); 
            putEventToCalDAV(newEv); 
            selectedEventId = newEv.id; 
            render();
        } else alert("You do not have permission to duplicate this event.");
    }
});

document.getElementById('btnDelete')?.addEventListener('click', () => {
    if (!isEditMode()) { alert("Please enable Edit Mode to delete events."); return; }
    if (selectedEventId) {
        let realId = selectedEventId.includes('_r') ? selectedEventId.split('_r')[0] : selectedEventId;
        let evToDel = events.find(e => e.id === realId);
        if (evToDel && !canEdit(evToDel)) { alert("You do not have permission to delete this event."); return; }
        events = events.filter(e => e.id !== realId);
        if (evToDel) deleteEventFromCalDAV(evToDel);
        selectedEventId = null; render();
    }
});

document.getElementById('btnFilterPeople')?.addEventListener('click', () => {
    const list = document.getElementById('peopleFilterList'); list.innerHTML = '';
    people.forEach((p, idx) => {
        if (!p) return;
        let div = document.createElement('div');
        let chk = document.createElement('input'); chk.type = 'checkbox'; chk.value = idx; chk.checked = visiblePeopleIndices.includes(idx); chk.id = 'chk_person_' + idx;
        let lbl = document.createElement('label'); lbl.htmlFor = 'chk_person_' + idx; lbl.innerText = p;
        div.appendChild(chk); div.appendChild(lbl); list.appendChild(div);
    });
    document.getElementById('peopleFilterModal').style.display = 'block';
});

document.getElementById('btnSavePeopleFilter')?.addEventListener('click', () => {
    visiblePeopleIndices = [];
    document.querySelectorAll('#peopleFilterList input[type="checkbox"]').forEach(chk => { if (chk.checked) visiblePeopleIndices.push(parseInt(chk.value)); });
    let myIdx = people.indexOf(currentUser);
    if (myIdx !== -1 && !visiblePeopleIndices.includes(myIdx)) visiblePeopleIndices.push(myIdx);
    saveVisiblePeople();
    document.getElementById('peopleFilterModal').style.display = 'none';
    render();
});

document.getElementById('btnCloneForUsers')?.addEventListener('click', () => {
    if (!isEditMode()) { alert("Please enable Edit Mode to clone events."); return; }
    if (!selectedEventId) { alert("Please select an event first."); return; }
    let realId = selectedEventId.includes('_r') ? selectedEventId.split('_r')[0] : selectedEventId;
    let ev = events.find(e => e.id === realId);
    if (!ev || !canEdit(ev)) { alert("You do not have permission to clone this event."); return; }
    
    const list = document.getElementById('cloneUsersList'); list.innerHTML = '';
    people.forEach((p, idx) => {
        if (!p) return;
        let div = document.createElement('div');
        let chk = document.createElement('input'); chk.type = 'checkbox'; chk.value = idx; chk.id = 'chk_clone_person_' + idx;
        let lbl = document.createElement('label'); lbl.htmlFor = 'chk_clone_person_' + idx; lbl.innerText = p;
        div.appendChild(chk); div.appendChild(lbl); list.appendChild(div);
    });
    document.getElementById('cloneUsersModal').style.display = 'block';
});

document.getElementById('btnDoCloneUsers')?.addEventListener('click', () => {
    let realId = selectedEventId.includes('_r') ? selectedEventId.split('_r')[0] : selectedEventId;
    let ev = events.find(e => e.id === realId);
    if (!ev) return;
    
    let checks = document.querySelectorAll('#cloneUsersList input[type="checkbox"]:checked');
    if (checks.length === 0) { alert("Please select at least one user."); return; }
    
    checks.forEach(chk => {
        let targetPersonIdx = parseInt(chk.value);
        let newEv = { ...ev, id: Date.now().toString() + Math.floor(Math.random() * 1000), personIdx: targetPersonIdx, color: colors[targetPersonIdx % colors.length] };
        events.push(newEv); putEventToCalDAV(newEv);
    });
    document.getElementById('cloneUsersModal').style.display = 'none';
    render();
});

document.getElementById('btnPrint')?.addEventListener('click', () => {
    if (!window.jspdf) { alert("jsPDF library not loaded. Ensure your jspdf.umd.min.js file is downloaded and placed in the same folder."); return; }
    const { jsPDF } = window.jspdf; const doc = new jsPDF({ orientation: 'portrait', unit: 'pt', format: 'letter' });
    let w = 612, h = 792; 
    let rPage = { left: 18, top: 18, right: w - 18, bottom: h - 18 };
    let titleH = 45, subH = 25; let gridTop = rPage.top + titleH + subH; let gridH = rPage.bottom - gridTop;
    let activeVisiblePeople = getEffectiveVisiblePeople();
    let displayEvents = typeof unrollEvents === 'function' ? unrollEvents() : events;

    doc.setFont("helvetica", "bold"); doc.setFontSize(18); doc.setTextColor(32, 33, 36);
    if (lblDateTitle) doc.text(lblDateTitle.innerText, rPage.left, rPage.top + 18);
    let viewDesc = document.getElementById('comboView').options[document.getElementById('comboView').selectedIndex].text;
    doc.setFont("helvetica", "normal"); doc.setFontSize(11); doc.setTextColor(112, 117, 122); doc.text(`Calendar Profile: ${viewDesc}`, rPage.left, rPage.top + 34);

    if (viewMode === 8) {
        let viewStart = new Date(currentDate); 
        viewStart.setDate(viewStart.getDate() - viewStart.getDay());
        
        let cols = [
            { x: rPage.left, w: 70, t: "Worker" },
            { x: rPage.left + 70, w: 35, t: "Hrs" },
            { x: rPage.left + 105, w: 67, t: "Sun" },
            { x: rPage.left + 172, w: 67, t: "Mon" },
            { x: rPage.left + 239, w: 67, t: "Tue" },
            { x: rPage.left + 306, w: 67, t: "Wed" },
            { x: rPage.left + 373, w: 67, t: "Thu" },
            { x: rPage.left + 440, w: 67, t: "Fri" },
            { x: rPage.left + 507, w: 67, t: "Sat" }
        ];
        
        doc.setFillColor(248, 249, 250);
        doc.rect(rPage.left, gridTop, rPage.right - rPage.left, 20, "F");
        doc.setFontSize(10); doc.setTextColor(0, 0, 0);
        doc.setFont("helvetica", "bold");
        
        for (let i = 0; i < cols.length; i++) {
            doc.text(cols[i].t, cols[i].x + 4, gridTop + 14);
        }
        
        let y = gridTop + 20;
        let gRestStr = document.getElementById('globalSchedRest')?.value;
        let gRestMins = (gRestStr && gRestStr.toLowerCase() !== 'clear') ? parseFloat(gRestStr) * 60 : 0;
        if (isNaN(gRestMins)) gRestMins = 0;
        
        activeVisiblePeople.forEach(pIdx => {
            if (y > rPage.bottom - 40) {
                doc.addPage();
                y = rPage.top + 20;
            }
            
            let shortName = people[pIdx] || `Person ${pIdx + 1}`;
            let contactMatch = contacts.find(c => c.username === shortName);
            let pName = (contactMatch && (contactMatch.fn || contactMatch.fullname)) ? (contactMatch.fn || contactMatch.fullname) : shortName;
            
            let uRestMins = gRestMins;
            if (schedUserData[pIdx] && schedUserData[pIdx].r && schedUserData[pIdx].r.toLowerCase() !== 'clear') {
                uRestMins = parseFloat(schedUserData[pIdx].r) * 60;
            }
            
            let totalPaidMins = 0;
            let dayStrs = [];
            for (let i = 0; i < 7; i++) {
                let d = new Date(viewStart); d.setDate(d.getDate() + i);
                let dayEvs = displayEvents.filter(e => e.date === formatDate(d) && e.personIdx === pIdx);
                let dailyWorkMins = 0;
                dayEvs.forEach(e => { if (!/\bOFF\b/.test((e.title || "").toUpperCase())) dailyWorkMins += e.duration; });
                if (dailyWorkMins > uRestMins) totalPaidMins += (dailyWorkMins - uRestMins);
                else totalPaidMins += dailyWorkMins;
                
                dayEvs.sort((a,b) => a.startMin - b.startMin);
                let evLines = dayEvs.map(e => {
                    if (/\bOFF\b/.test((e.title||"").toUpperCase())) return e.title;
                    return `${minToTimeStr(e.startMin)}-${minToTimeStr(e.startMin+e.duration)}`;
                });
                dayStrs.push(evLines);
            }
            
            let hrsStr = (totalPaidMins / 60).toFixed(1) + "h";
            
            let maxLines = 1;
            dayStrs.forEach(lines => { if (lines.length > maxLines) maxLines = lines.length; });
            let rowH = (maxLines * 12) + 8;
            
            doc.setDrawColor(204, 204, 204); doc.line(rPage.left, y, rPage.right, y);
            
            doc.setFontSize(9);
            doc.setFont("helvetica", "bold");
            doc.text(pName.substring(0, 15), cols[0].x + 4, y + 12);
            doc.text(hrsStr, cols[1].x + 4, y + 12);
            
            doc.setFont("helvetica", "normal");
            for (let i = 0; i < 7; i++) {
                let lines = dayStrs[i];
                for (let j = 0; j < lines.length; j++) {
                    doc.text(lines[j], cols[i+2].x + 2, y + 12 + (j*12));
                }
            }
            y += rowH;
        });
        
        doc.setDrawColor(204, 204, 204); doc.line(rPage.left, y, rPage.right, y);
        
        let wkDate = new Date(viewStart); wkDate.setHours(0,0,0,0);
        let weekNo = Math.ceil((((wkDate - new Date(wkDate.getFullYear(), 0, 1)) / 86400000) + new Date(wkDate.getFullYear(), 0, 1).getDay() + 1) / 7);
        let pdfName = `${wkDate.getFullYear()}${String(wkDate.getMonth()+1).padStart(2,'0')}${String(wkDate.getDate()).padStart(2,'0')}_WK${weekNo}_Schedule.pdf`;
        doc.save(pdfName);
        return;
    }

    let cols = getColCount(); let activeCols = isPeopleView() ? activeVisiblePeople.length : cols; if (activeCols === 0) activeCols = 1;
    let timeColW = 54; let dayColW = (rPage.right - rPage.left - timeColW) / activeCols;
    let viewStart = new Date(currentDate); if (viewMode === 3) viewStart.setDate(viewStart.getDate() - viewStart.getDay());

    doc.setFillColor(248, 249, 250); doc.rect(rPage.left + timeColW, rPage.top + titleH, rPage.right - (rPage.left + timeColW), subH, "F");
    doc.setLineWidth(0.5);
    
    for (let i = 0; i <= 24; i++) {
        let y = gridTop + (i * 60) * (gridH / 1440.0);
        doc.setDrawColor(204, 204, 204); doc.line(rPage.left + timeColW, y, rPage.right, y);
        if (i > 0 && i < 24) {
            doc.setFont("helvetica", "normal"); doc.setFontSize(9); doc.setTextColor(112, 117, 122);
            doc.text(minToTimeStr(i * 60), rPage.left + timeColW - 6, y + 3, { align: "right" });
        }
        if (i < 24) {
            let hy = gridTop + (i * 60 + 30) * (gridH / 1440.0);
            doc.setDrawColor(232, 232, 232); doc.line(rPage.left + timeColW, hy, rPage.right, hy);
        }
    }

    doc.setDrawColor(204, 204, 204);
    for (let c = 0; c <= activeCols; c++) {
        let x = rPage.left + timeColW + (c * dayColW); doc.line(x, rPage.top + titleH, x, rPage.bottom);
        if (c < activeCols) {
            let hdr = "";
            if (isPeopleView()) {
                let shortName = people[activeVisiblePeople[c]];
                let contactMatch = contacts.find(contact => contact.username === shortName);
                hdr = (contactMatch && (contactMatch.fn || contactMatch.fullname)) ? (contactMatch.fn || contactMatch.fullname) : shortName;
            } else {
                let d = new Date(viewStart); d.setDate(d.getDate() + c); hdr = d.toLocaleDateString(undefined, {weekday:'short', month:'numeric', day:'numeric'});
            }
            doc.setFont("helvetica", "bold"); doc.setFontSize(11); doc.setTextColor(60, 64, 67); doc.text(hdr, x + (dayColW/2), rPage.top + titleH + 16, { align: "center" });
        }
    }

    displayEvents.forEach(ev => {
        let colIdx = -1;
        if (isPeopleView()) { 
            if (ev.date === formatDate(currentDate)) { let dispIdx = activeVisiblePeople.indexOf(ev.personIdx); if (dispIdx !== -1) colIdx = dispIdx; }
        } else {
            let evDate = new Date(ev.date.replace(/\//g, '-') + 'T12:00:00');
            let diffDays = Math.round((evDate.getTime() - new Date(viewStart.getFullYear(), viewStart.getMonth(), viewStart.getDate(), 12, 0, 0).getTime()) / 86400000);
            if (diffDays >= 0 && diffDays < activeCols) colIdx = diffDays;
        }

        if (colIdx >= 0 && colIdx < activeCols) {
            let top = gridTop + (ev.startMin * (gridH / 1440.0));
            let bot = gridTop + ((ev.startMin + ev.duration) * (gridH / 1440.0));
            let left = rPage.left + timeColW + (colIdx * dayColW) + 2; let right = left + dayColW - 4;
            let ew = right - left; let eh = bot - top;
            doc.setFillColor(ev.color); doc.roundedRect(left, top, ew, eh, 3, 3, "F");
            if (eh > 18) {
                doc.setFont("helvetica", "bold"); doc.setFontSize(9); doc.setTextColor(255, 255, 255);
                let titleStr = ev.title.length > 20 ? ev.title.substring(0, 17) + "..." : ev.title;
                doc.text(titleStr, left + 4, top + 10, { maxWidth: ew - 8 });
                doc.setFont("helvetica", "normal"); doc.text(`${minToTimeStr(ev.startMin)} - ${minToTimeStr(ev.startMin+ev.duration)}`, left + 4, top + 20);
            }
        }
    });

    let pdfName = "Schedule.pdf";
    let wkDate = new Date(viewStart); wkDate.setHours(0,0,0,0);
    let weekNo = Math.ceil((((wkDate - new Date(wkDate.getFullYear(), 0, 1)) / 86400000) + new Date(wkDate.getFullYear(), 0, 1).getDay() + 1) / 7);
    let datePrefix = `${wkDate.getFullYear()}${String(wkDate.getMonth()+1).padStart(2,'0')}${String(wkDate.getDate()).padStart(2,'0')}_WK${weekNo}`;

    if (activeVisiblePeople.length === 1) {
        let shortName = people[activeVisiblePeople[0]] || currentUser || "Calendar";
        let contactMatch = contacts.find(c => c.username === shortName);
        let finalName = (contactMatch && (contactMatch.fn || contactMatch.fullname)) ? (contactMatch.fn || contactMatch.fullname) : shortName;
        pdfName = `${datePrefix}_${finalName.replace(/[^a-z0-9]/gi, '_')}.pdf`;
    } else if (currentUser) {
        pdfName = `${datePrefix}_${currentUser.replace(/[^a-z0-9]/gi, '_')}.pdf`;
    } else { pdfName = `${datePrefix}_Schedule.pdf`; }
    doc.save(pdfName);
});

document.getElementById('btnExport')?.addEventListener('click', () => {
    let activeVisiblePeople = getEffectiveVisiblePeople();
    let displayEvents = typeof unrollEvents === 'function' ? unrollEvents() : events;
    let content = "";
    let fileName = "";

    if (viewMode === 8) {
        let viewStart = new Date(currentDate); 
        viewStart.setDate(viewStart.getDate() - viewStart.getDay());
        
        let pad = (str, len) => (str.length > len ? str.substring(0, len) : str.padEnd(len, ' '));
        
        content = "====================================================================================================================================\r\n";
        content += "WORKER       | HRS  | SUN            | MON            | TUE            | WED            | THU            | FRI            | SAT            \r\n";
        content += "====================================================================================================================================\r\n";
        
        let gRestStr = document.getElementById('globalSchedRest')?.value;
        let gRestMins = (gRestStr && gRestStr.toLowerCase() !== 'clear') ? parseFloat(gRestStr) * 60 : 0;
        if (isNaN(gRestMins)) gRestMins = 0;

        activeVisiblePeople.forEach(pIdx => {
            let shortName = people[pIdx] || `Person ${pIdx + 1}`;
            let contactMatch = contacts.find(c => c.username === shortName);
            let pName = (contactMatch && (contactMatch.fn || contactMatch.fullname)) ? (contactMatch.fn || contactMatch.fullname) : shortName;
            
            let uRestMins = gRestMins;
            if (schedUserData[pIdx] && schedUserData[pIdx].r && schedUserData[pIdx].r.toLowerCase() !== 'clear') uRestMins = parseFloat(schedUserData[pIdx].r) * 60;
            
            let totalPaidMins = 0;
            let dailyData = [];
            
            for (let i = 0; i < 7; i++) {
                let d = new Date(viewStart); d.setDate(d.getDate() + i);
                let dayEvs = displayEvents.filter(e => e.date === formatDate(d) && e.personIdx === pIdx);
                let dailyWorkMins = 0;
                dayEvs.forEach(e => { if (!/\bOFF\b/.test((e.title || "").toUpperCase())) dailyWorkMins += e.duration; });
                if (dailyWorkMins > uRestMins) totalPaidMins += (dailyWorkMins - uRestMins);
                else totalPaidMins += dailyWorkMins;
                
                dayEvs.sort((a,b) => a.startMin - b.startMin);
                let evStrs = dayEvs.map(e => {
                    if (/\bOFF\b/.test((e.title||"").toUpperCase())) return e.title;
                    return `${minToTimeStr(e.startMin)}-${minToTimeStr(e.startMin+e.duration)}`;
                });
                dailyData.push(evStrs.join(', ') || "");
            }
            
            let hrsStr = (totalPaidMins / 60).toFixed(1) + "h";
            content += `${pad(pName, 12)} | ${pad(hrsStr, 4)} | ${pad(dailyData[0], 14)} | ${pad(dailyData[1], 14)} | ${pad(dailyData[2], 14)} | ${pad(dailyData[3], 14)} | ${pad(dailyData[4], 14)} | ${pad(dailyData[5], 14)} | ${pad(dailyData[6], 14)}\r\n`;
        });
        
        content += "====================================================================================================================================\r\n";
        
        let wkDate = new Date(viewStart); wkDate.setHours(0,0,0,0);
        let weekNo = Math.ceil((((wkDate - new Date(wkDate.getFullYear(), 0, 1)) / 86400000) + new Date(wkDate.getFullYear(), 0, 1).getDay() + 1) / 7);
        fileName = `${wkDate.getFullYear()}${String(wkDate.getMonth()+1).padStart(2,'0')}${String(wkDate.getDate()).padStart(2,'0')}_WK${weekNo}_Schedule.txt`;

    } else {
        content = "========================================\r\n           EVENTS SCHEDULE            \r\n========================================\r\n\r\n";
        let validEvents = displayEvents.filter(e => {
            if (e.date < formatDate(new Date())) return false;
            if (activeVisiblePeople.length > 0 && !activeVisiblePeople.includes(e.personIdx)) return false;
            return true;
        });

        let sorted = validEvents.sort((a,b) => a.date.localeCompare(b.date) || a.startMin - b.startMin);
        sorted.forEach(e => {
            let p = people[e.personIdx] || "";
            content += `${e.title}\r\n${e.date}       ${minToTimeStr(e.startMin)} - ${minToTimeStr(e.startMin+e.duration)}       ${p}\r\n----------------------------------------\r\n`;
        });
        
        let viewStart = new Date(currentDate); 
        if (viewMode === 3) viewStart.setDate(viewStart.getDate() - viewStart.getDay());
        let wkDate = new Date(viewStart); wkDate.setHours(0,0,0,0);
        let weekNo = Math.ceil((((wkDate - new Date(wkDate.getFullYear(), 0, 1)) / 86400000) + new Date(wkDate.getFullYear(), 0, 1).getDay() + 1) / 7);
        let datePrefix = `${wkDate.getFullYear()}${String(wkDate.getMonth()+1).padStart(2,'0')}${String(wkDate.getDate()).padStart(2,'0')}_WK${weekNo}`;
        
        fileName = `${datePrefix}.txt`;
        if (activeVisiblePeople.length === 1) {
            let shortName = people[activeVisiblePeople[0]] || currentUser || "Calendar";
            let contactMatch = contacts.find(c => c.username === shortName);
            let finalName = (contactMatch && (contactMatch.fn || contactMatch.fullname)) ? (contactMatch.fn || contactMatch.fullname) : shortName;
            fileName = `${datePrefix}_${finalName.replace(/[^a-z0-9]/gi, '_').toUpperCase()}.txt`;
        }
    }

    let blob = new Blob([content], {type: "text/plain"});
    let url = URL.createObjectURL(blob); let a = document.createElement('a'); a.href = url; a.download = fileName;
    a.click(); URL.revokeObjectURL(url);
});

updateLayout(); downloadUpdates();

async function loadSetting(key, defaultVal) {
    try {
        let res = await fetch(`${window.location.origin}/api/setting?key=${key}`, { headers: { 'Authorization': getAuthHeader() } });
        if (res.ok) { let text = await res.text(); return text.trim() !== "" ? text.trim() : defaultVal; }
    } catch(e) {}
    return defaultVal;
}

async function saveSetting(key, val) {
    try { await fetch(`${window.location.origin}/api/setting`, { method: 'PUT', headers: { 'Authorization': getAuthHeader() }, body: `${key}=${val}` }); } catch(e) {}
}

window.addEventListener('DOMContentLoaded', async () => {
    let savedChecked = await loadSetting('sched_checked', 'NONE');
    unselectedSchedUsers.clear();
    if (savedChecked === 'NONE') {
        people.forEach((_, idx) => unselectedSchedUsers.add(idx));
    } else {
        let checkedArr = savedChecked.split(',').map(Number).filter(n => !isNaN(n));
        people.forEach((_, idx) => {
            if (!checkedArr.includes(idx)) unselectedSchedUsers.add(idx);
        });
    }
    hasLoadedSchedUsers = true;
    populateTempFilter();

    let schedHeader = document.getElementById('schedHeaderRow');
    if (schedHeader) {
        schedHeader.innerHTML = `
            <div style="width:15px;"></div>
            <div style="width:120px; cursor:pointer;" onclick="sortSchedList('user')">User</div>
            <div style="width:120px; cursor:pointer;" onclick="sortSchedList('coverage')">Coverage Hours</div>
            <div style="width:120px; cursor:pointer;" onclick="sortSchedList('pref')">Preferred</div>
            <div style="width:85px; cursor:pointer;" onclick="sortSchedList('days')">Days</div>
            <div style="width:75px; cursor:pointer;" onclick="sortSchedList('rest')">Rest(h)</div>
            <div style="width:75px; cursor:pointer;" onclick="sortSchedList('daily')">Daily</div>
            <div style="width:75px; cursor:pointer;" onclick="sortSchedList('weekly')">Weekly</div>
        `;
    }
    
    let btnContainer = document.getElementById('btnMakeSchedule')?.parentElement;
    if (btnContainer && !document.getElementById('btnSchedClearWeek')) {
        btnContainer.insertAdjacentHTML('afterbegin', `<button id="btnSchedClearWeek" style="background:#E53935; color:#fff; border:none; padding:8px 16px; border-radius:3px; cursor:pointer;">Clear</button>`);
        
        document.getElementById('btnSchedClearWeek').addEventListener('click', async () => {
            let activeMode = document.getElementById('comboDefaultEvent')?.value || "WORKING";
            if (!confirm(`Are you sure you want to clear all ${activeMode} events for this week?`)) return;
            window.isScheduling = true;
            try {
                let weekStartStr = document.getElementById('schedWeekSelect').value;
                let startDate = new Date(weekStartStr.replace(/\//g, '-') + 'T12:00:00');
                
                let qToUpdate = new Set();
                let eventsToKeep = [];
                let eventsDeleted = 0;
                
                events.forEach(e => {
                    let evDate = new Date(e.date.replace(/\//g, '-') + 'T12:00:00');
                    let diffDays = Math.round((evDate.getTime() - startDate.getTime()) / 86400000);
                    
                    if (diffDays >= 0 && diffDays < 7) {
                        let eTitle = (e.title || "").toUpperCase();
                        if (eTitle.includes(activeMode.toUpperCase())) {
                            qToUpdate.add(getStorageKey(e.date));
                            eventsDeleted++;
                        } else eventsToKeep.push(e);
                    } else eventsToKeep.push(e);
                });
                
                if (eventsDeleted > 0) {
                    events = eventsToKeep;
                    for (let q of qToUpdate) {
                        let dayEvents = events.filter(e => getStorageKey(e.date) === q);
                        try {
                            if (dayEvents.length === 0) await fetch(`${CALDAV_URL}${q}.json`, { method: 'DELETE', headers: { 'Authorization': getAuthHeader() } });
                            else await fetch(`${CALDAV_URL}${q}.json`, { method: 'PUT', headers: { 'Content-Type': 'application/json', 'Authorization': getAuthHeader() }, body: JSON.stringify(dayEvents) });
                        } catch(err) {}
                    }
                    render();
                }
            } finally { window.isScheduling = false; }
        });
    }

    let defText = await loadSetting('default_event', 'WORKING');
    let cbo = document.getElementById('comboDefaultEvent'); if (cbo) cbo.value = defText;

    let gw = await loadSetting('global_weekly', '40'); let gda = await loadSetting('global_daily', '8');
    let gr = await loadSetting('global_rest', '0.5'); let gc = await loadSetting('global_coverage', '9-5');
    let gp = await loadSetting('global_pref', 'Default'); let gd = await loadSetting('global_days', '2,3,4,5,6');
    
    if (document.getElementById('globalSchedWeekly')) document.getElementById('globalSchedWeekly').value = gw;
    if (document.getElementById('globalSchedDaily')) document.getElementById('globalSchedDaily').value = gda;
    if (document.getElementById('globalSchedRest')) document.getElementById('globalSchedRest').value = gr;
    if (document.getElementById('globalSchedCoverage')) document.getElementById('globalSchedCoverage').value = gc;
    if (document.getElementById('globalSchedPref')) document.getElementById('globalSchedPref').value = gp;
    if (document.getElementById('globalSchedDays')) document.getElementById('globalSchedDays').value = gd;
    
    ['globalSchedWeekly', 'globalSchedDaily', 'globalSchedRest', 'globalSchedCoverage', 'globalSchedPref', 'globalSchedDays'].forEach(id => {
        document.getElementById(id)?.addEventListener('change', (e) => {
            let type = e.target.dataset.type;
            if (!validateSchedInput(type, e.target.value)) {
                alert(`Invalid input format for ${type}.`);
                e.target.value = '';
                return;
            }
            
            let key = id === 'globalSchedWeekly' ? 'global_weekly' : id === 'globalSchedDaily' ? 'global_daily' : id === 'globalSchedRest' ? 'global_rest' :
                      id === 'globalSchedDays' ? 'global_days' : id === 'globalSchedCoverage' ? 'global_coverage' : 'global_pref';
            
            if (e.target.value.toLowerCase() === 'clear') {
                let cls = id === 'globalSchedWeekly' ? '.sched-weekly' : id === 'globalSchedDaily' ? '.sched-daily' : id === 'globalSchedRest' ? '.sched-rest' :
                          id === 'globalSchedDays' ? '.sched-days' : id === 'globalSchedCoverage' ? '.sched-coverage' : '.sched-pref';
                document.querySelectorAll(cls).forEach(el => { el.value = ''; el.dispatchEvent(new Event('change', { bubbles: true })); });
                e.target.value = ''; saveSetting(key, "");
            } else {
                saveSetting(key, e.target.value);
            }
        });
    });
});

document.getElementById('comboDefaultEvent')?.addEventListener('change', (e) => { saveSetting('default_event', e.target.value); });

let currentSchedYear = new Date().getFullYear();
let unselectedSchedUsers = new Set();
let schedUserData = {}; 
let hasLoadedSchedUsers = false;

document.getElementById('btnScheduling')?.addEventListener('click', async () => {
    populateWeeks();
    let defFilter = await loadSetting('sched_filter', '');
    document.getElementById('schedUserFilter').value = defFilter;
    
    let savedUserData = await loadSetting('sched_user_data', '{}');
    try {
        schedUserData = JSON.parse(savedUserData);
    } catch(e) {
        schedUserData = {};
    }

    renderSchedUsers();
    document.getElementById('schedulingModal').style.display = 'flex';
});

function saveSchedChecked() {
    let checked = people.map((_, i) => i).filter(i => !unselectedSchedUsers.has(i));
    saveSetting('sched_checked', checked.join(','));
}

function populateWeeks() {
    let sel = document.getElementById('schedWeekSelect'); sel.innerHTML = '';
    let d = new Date(currentSchedYear, 0, 1); d.setDate(d.getDate() - d.getDay()); 
    let w = 1, selIdx = 0;
    let disp = new Date(currentDate); disp.setDate(disp.getDate() - disp.getDay());
    let dispWeekStr = formatDate(disp);
    
    while (d.getFullYear() <= currentSchedYear) {
        if (d.getFullYear() === currentSchedYear || w === 1) {
            let opt = document.createElement('option');
            let dtStr = formatDate(d); opt.value = dtStr; opt.innerText = `${dtStr} W${String(w).padStart(2, '0')}`;
            sel.appendChild(opt); if (dtStr === dispWeekStr) selIdx = sel.options.length - 1; w++;
        }
        d.setDate(d.getDate() + 7);
    }
    if (sel.options.length > selIdx) sel.selectedIndex = selIdx;
}

document.getElementById('btnPrevWeek')?.addEventListener('click', () => {
    let sel = document.getElementById('schedWeekSelect');
    if (sel.selectedIndex > 0) sel.selectedIndex--;
    else { currentSchedYear--; populateWeeks(); document.getElementById('schedWeekSelect').selectedIndex = document.getElementById('schedWeekSelect').options.length - 1; }
});

document.getElementById('btnNextWeek')?.addEventListener('click', () => {
    let sel = document.getElementById('schedWeekSelect');
    if (sel.selectedIndex < sel.options.length - 1) sel.selectedIndex++;
    else { currentSchedYear++; populateWeeks(); document.getElementById('schedWeekSelect').selectedIndex = 0; }
});

document.getElementById('schedWeekSelect')?.addEventListener('change', () => {
    let yr = parseInt(document.getElementById('schedWeekSelect').value.substring(0,4));
    if (yr !== currentSchedYear) { currentSchedYear = yr; populateWeeks(); }
});

document.getElementById('schedUserFilter')?.addEventListener('input', (e) => {
    saveSetting('sched_filter', e.target.value); renderSchedUsers();
});

let currentSortCol = 'user';
let currentSortDir = 1;

window.sortSchedList = function(col) {
    if (currentSortCol === col) currentSortDir *= -1;
    else { currentSortCol = col; currentSortDir = 1; }
    renderSchedUsers();
};

function renderSchedUsers() {
    let filter = document.getElementById('schedUserFilter').value.toLowerCase();
    let list = document.getElementById('schedUserList'); list.innerHTML = '';
    
    let usersArr = people.map((p, i) => ({
        pIdx: i, name: p,
        w: schedUserData[i]?.w || '', da: schedUserData[i]?.da || '', r: schedUserData[i]?.r || '',
        c: schedUserData[i]?.c || '', p_val: schedUserData[i]?.p || '', d: schedUserData[i]?.d || ''
    })).filter(u => u.name && u.name.toLowerCase().includes(filter));

    usersArr.sort((a, b) => {
        let isPersonA = a.name.startsWith("Person "); let isPersonB = b.name.startsWith("Person ");
        if (isPersonA && !isPersonB) return 1; if (!isPersonA && isPersonB) return -1;

        let valA = a.name.toLowerCase(), valB = b.name.toLowerCase();
        if (currentSortCol === 'weekly') { valA = parseFloat(a.w) || 0; valB = parseFloat(b.w) || 0; }
        else if (currentSortCol === 'daily') { valA = parseFloat(a.da) || 0; valB = parseFloat(b.da) || 0; }
        else if (currentSortCol === 'rest') { valA = parseFloat(a.r) || 0; valB = parseFloat(b.r) || 0; }
        else if (currentSortCol === 'coverage') { valA = a.c; valB = b.c; }
        else if (currentSortCol === 'pref') { valA = a.p_val; valB = b.p_val; }
        else if (currentSortCol === 'days') { valA = a.d; valB = b.d; }
        
        if (valA < valB) return -1 * currentSortDir; if (valA > valB) return 1 * currentSortDir; return 0;
    });

    usersArr.forEach(u => {
        let div = document.createElement('div');
        div.style.display = 'flex'; div.style.gap = '10px'; div.style.marginBottom = '6px'; div.style.alignItems = 'center'; div.style.padding = '0 10px';
        div.dataset.pidx = u.pIdx; let isChecked = !unselectedSchedUsers.has(u.pIdx);
        
        div.innerHTML = `
            <input type="checkbox" class="sched-chk" value="${u.pIdx}" style="width:15px; margin:0;" ${isChecked ? 'checked' : ''}>
            <label style="width:120px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${u.name}">${u.name}</label>
            <input type="text" class="sched-coverage" data-type="coverage" list="dl-coverage" style="width:120px; padding:2px;">
            <input type="text" class="sched-pref" data-type="pref" list="dl-pref" style="width:120px; padding:2px;">
            <input type="text" class="sched-days" data-type="days" list="dl-days" style="width:85px; padding:2px;">
            <input type="text" class="sched-rest" data-type="rest" list="dl-rest" style="width:75px; padding:2px;">
            <input type="text" class="sched-daily" data-type="daily" list="dl-daily" style="width:75px; padding:2px;">
            <input type="text" class="sched-weekly" data-type="weekly" list="dl-weekly" style="width:75px; padding:2px;">
        `;
        list.appendChild(div);
        
        div.querySelector('.sched-coverage').value = u.c;
        div.querySelector('.sched-pref').value = u.p_val;
        div.querySelector('.sched-days').value = u.d;
        div.querySelector('.sched-rest').value = u.r;
        div.querySelector('.sched-daily').value = u.da;
        div.querySelector('.sched-weekly').value = u.w;
    });
}

document.getElementById('btnSchedSelectAll')?.addEventListener('click', () => {
    unselectedSchedUsers.clear(); document.querySelectorAll('.sched-chk').forEach(c => c.checked = true);
    saveSchedChecked();
});

document.getElementById('btnSchedSelectNone')?.addEventListener('click', () => {
    document.querySelectorAll('.sched-chk').forEach(c => { c.checked = false; unselectedSchedUsers.add(parseInt(c.value)); });
    saveSchedChecked();
});

let saveUserDataTimeout = null;
function deferredSaveUserData() {
    if (saveUserDataTimeout) clearTimeout(saveUserDataTimeout);
    saveUserDataTimeout = setTimeout(() => {
        saveSetting('sched_user_data', JSON.stringify(schedUserData));
    }, 300);
}

document.getElementById('schedUserList')?.addEventListener('change', (e) => {
    let row = e.target.closest('div[data-pidx]');
    if (row && e.target.tagName === 'INPUT' && e.target.dataset.type) {
        let type = e.target.dataset.type;
        if (!validateSchedInput(type, e.target.value)) {
            alert(`Invalid input format for ${type}.`);
            e.target.value = '';
            return;
        }
        
        let pIdx = parseInt(row.dataset.pidx);
        if (!schedUserData[pIdx]) schedUserData[pIdx] = {};
        if (e.target.classList.contains('sched-weekly')) schedUserData[pIdx].w = e.target.value;
        if (e.target.classList.contains('sched-daily')) schedUserData[pIdx].da = e.target.value;
        if (e.target.classList.contains('sched-rest')) schedUserData[pIdx].r = e.target.value;
        if (e.target.classList.contains('sched-coverage')) schedUserData[pIdx].c = e.target.value;
        if (e.target.classList.contains('sched-pref')) schedUserData[pIdx].p = e.target.value;
        if (e.target.classList.contains('sched-days')) schedUserData[pIdx].d = e.target.value;
        
        deferredSaveUserData();
    }
    if (e.target.classList.contains('sched-chk')) {
        let idx = parseInt(e.target.value);
        if (e.target.checked) unselectedSchedUsers.delete(idx); else unselectedSchedUsers.add(idx);
        saveSchedChecked();
    }
});

function parseTimeStr(t) {
    if (!t) return NaN;
    let p = t.split(':');
    let h = parseInt(p[0]); let m = p.length > 1 ? parseInt(p[1]) : 0;
    if (isNaN(m)) m = 0;
    if (h >= 1 && h <= 7) h += 12;
    return h * 60 + m;
}

document.getElementById('btnMakeSchedule')?.addEventListener('click', async () => {
    let activeMode = document.getElementById('comboDefaultEvent')?.value || "WORKING";
    if (!confirm(`Are you sure you want to Create a Schedule using ${activeMode}?`)) return;

    window.isScheduling = true;
    try {
        let weekSelectEl = document.getElementById('schedWeekSelect');
        if (!weekSelectEl) throw new Error("schedWeekSelect not found.");
        let weekStartStr = weekSelectEl.value || "";
        
        let [y, m, d] = weekStartStr.split(/[-/]/);
        if (y && y.length !== 4) { 
            let temp = y; y = d; d = temp; 
        }
        let startDate = new Date(y, (parseInt(m) - 1).toString(), d, 12, 0, 0);

        const isOffEvent = (e) => /\bOFF\b/i.test(e.title || "") || (e.title || "").toUpperCase().includes("OFF");

        const parseTimePart = (tStr) => {
            let m = tStr.trim().match(/^(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$/i);
            if (!m) return null;
            let h = parseInt(m[1], 10);
            let mins = m[2] ? parseInt(m[2], 10) : 0;
            let mer = m[3] ? m[3].toLowerCase() : null;
            let hasLeadingZero = m[1].length === 2 && m[1].startsWith('0');
            if (h > 24 || mins < 0 || mins >= 60) return null;
            return { h, mins, mer, hasLeadingZero };
        };

        const parseShiftList = (rawStr) => {
            if (!rawStr || typeof rawStr !== 'string') return [];
            let trimmed = rawStr.trim();
            if (!trimmed || /^(default|clear|none)$/i.test(trimmed)) return [];

            let parts = trimmed.split(',');
            let explicitEntries = [];
            let defaultEntries = [];
            let explicitDaysSet = new Set();

            for (let part of parts) {
                part = part.trim();
                if (!part) continue;

                let dayIdx = null;
                let rangeStr = part;

                if (part.includes('=')) {
                    let eqParts = part.split('=');
                    if (eqParts.length !== 2) continue;
                    let dNum = parseInt(eqParts[0].trim(), 10);
                    if (isNaN(dNum) || dNum < 1 || dNum > 7) continue;
                    dayIdx = dNum - 1; 
                    rangeStr = eqParts[1].trim();
                }

                let dashParts = rangeStr.split('-');
                if (dashParts.length !== 2) continue;

                let sToken = parseTimePart(dashParts[0]);
                let eToken = parseTimePart(dashParts[1]);
                if (!sToken || !eToken) continue;

                let sH = sToken.h;
                let eH = eToken.h;

                if (sToken.mer === 'am' && sH === 12) sH = 0;
                else if (sToken.mer === 'pm' && sH < 12) sH += 12;

                if (eToken.mer === 'am' && eH === 12) eH = 0;
                else if (eToken.mer === 'pm' && eH < 12) eH += 12;

                if (!sToken.mer && !sToken.hasLeadingZero && sH >= 1 && sH <= 5) {
                    let tentativeStartMin = (sH + 12) * 60 + sToken.mins;
                    let rawEndMin = eH * 60 + eToken.mins;
                    if (!(eH > 12 && tentativeStartMin >= rawEndMin)) {
                        sH += 12;
                    }
                }

                let startMin = sH * 60 + sToken.mins;
                let endMin = eH * 60 + eToken.mins;

                if (!eToken.mer && !eToken.hasLeadingZero && eH <= 12 && endMin <= startMin) {
                    eH += 12;
                    endMin = eH * 60 + eToken.mins;
                }

                let span = endMin - startMin;
                if (span <= 0) continue;

                if (dayIdx !== null) {
                    explicitDaysSet.add(dayIdx);
                    explicitEntries.push({ start: startMin, span: span, days: [dayIdx] });
                } else {
                    defaultEntries.push({ start: startMin, span: span });
                }
            }

            let fallbackDays = [0, 1, 2, 3, 4, 5, 6].filter(d => !explicitDaysSet.has(d));
            let finalShifts = [...explicitEntries];
            if (defaultEntries.length > 0 && fallbackDays.length > 0) {
                for (let def of defaultEntries) {
                    finalShifts.push({ start: def.start, span: def.span, days: fallbackDays });
                }
            }
            return finalShifts;
        };

        const findAvailableSlots = (rangeStartMin, rangeEndMin, offEvents) => {
            let sortedOffs = [...offEvents]
                .map(e => ({ startMin: Number(e.startMin), duration: Number(e.duration) }))
                .sort((a, b) => a.startMin - b.startMin);
            let currentTime = Number(rangeStartMin);
            let endMin = Number(rangeEndMin);
            let availableChunks = [];
            
            for (let event of sortedOffs) {
                let evEndMin = event.startMin + event.duration;
                if (evEndMin <= currentTime || event.startMin >= endMin) continue;
                
                if (event.startMin > currentTime) {
                    availableChunks.push({ start: currentTime, end: Math.min(event.startMin, endMin) });
                }
                
                if (evEndMin > currentTime) {
                    currentTime = evEndMin;
                }
            }
            
            if (currentTime < endMin) {
                availableChunks.push({ start: currentTime, end: endMin });
            }
            return availableChunks;
        };

        let weekDates = [];
        for (let dayOffset = 0; dayOffset < 7; dayOffset++) {
            let curDate = new Date(startDate);
            curDate.setDate(curDate.getDate() + dayOffset);
            weekDates.push(formatDate(curDate));
        }

        let qToUpdate = new Set();

        let myIdx = people.indexOf(currentUser);
        let tempFilterVal = document.getElementById('comboTempPersonFilter')?.value;
        if (myIdx !== -1 && unselectedSchedUsers.has(myIdx) && tempFilterVal !== "" && tempFilterVal !== undefined) {
            let targetIdx = parseInt(tempFilterVal, 10);
            if (!isNaN(targetIdx)) {
                events.forEach(e => {
                    if (weekDates.includes(e.date) && Number(e.personIdx) === myIdx && isOffEvent(e)) {
                        e.personIdx = targetIdx;
                        e.color = colors[targetIdx % colors.length];
                        qToUpdate.add(getStorageKey(e.date));
                    }
                });
            }
        }

        let allEvsInitial = typeof unrollEvents === 'function' ? unrollEvents() : events;
        let preTrimmedEvents = [];
        for (let ev of events) {
            if (weekDates.includes(ev.date) && !isOffEvent(ev)) {
                let dailyOffs = allEvsInitial.filter(e => 
                    getStorageKey(e.date) === getStorageKey(ev.date) && 
                    Number(e.personIdx) === Number(ev.personIdx) && 
                    isOffEvent(e)
                );
                if (dailyOffs.length > 0) {
                    qToUpdate.add(getStorageKey(ev.date));
                    let validChunks = findAvailableSlots(ev.startMin, Number(ev.startMin) + Number(ev.duration), dailyOffs);
                    validChunks.forEach(chunk => {
                        if (chunk.end - chunk.start > 0) {
                            preTrimmedEvents.push({
                                ...ev,
                                id: crypto.randomUUID ? crypto.randomUUID() : (Date.now().toString() + Math.random()),
                                startMin: chunk.start,
                                duration: chunk.end - chunk.start
                            });
                        }
                    });
                } else {
                    preTrimmedEvents.push(ev);
                }
            } else {
                preTrimmedEvents.push(ev);
            }
        }
        events = preTrimmedEvents;

        let allEvs = typeof unrollEvents === 'function' ? unrollEvents() : events;
        
        let gWeekly = document.getElementById('globalSchedWeekly')?.value || "40";
        let gDaily = document.getElementById('globalSchedDaily')?.value || "8";
        let gRest = document.getElementById('globalSchedRest')?.value || "0.5";
        let gCoverage = document.getElementById('globalSchedCoverage')?.value || "9-5";
        let gPref = document.getElementById('globalSchedPref')?.value || "Default";
        let gDays = document.getElementById('globalSchedDays')?.value || "2,3,4,5,6";

        let users = [];

        for (let pIdx = 0; pIdx < people.length; pIdx++) {
            if (!people[pIdx]) continue;
            if (unselectedSchedUsers.has(pIdx)) continue;
            
            let data = schedUserData[pIdx] || {};
            let pW = data.w; let pDa = data.da;
            let pR = data.r; let pC_val = data.c;
            let pP_val = data.p; let pD = data.d;
            
            let hasCustomPref = (pP_val && pP_val.toLowerCase() !== 'clear' && pP_val.toLowerCase() !== 'default');
            
            if (!pW || pW.toLowerCase() === 'clear') pW = gWeekly; 
            if (!pDa || pDa.toLowerCase() === 'clear') pDa = gDaily;
            if (!pR || pR.toLowerCase() === 'clear') pR = gRest; 
            if (!pC_val || pC_val.toLowerCase() === 'clear') pC_val = gCoverage;
            if (!pP_val || pP_val.toLowerCase() === 'clear') pP_val = gPref; 
            if (!pD || pD.toLowerCase() === 'clear') pD = gDays;
            
            if (!pW || !pC_val || !pP_val) continue;

            let weeklyMins = parseFloat(pW) * 60; if (isNaN(weeklyMins) || weeklyMins <= 0) continue;
            let dailyMaxMins = parseFloat(pDa) * 60; if (isNaN(dailyMaxMins) || dailyMaxMins <= 0) dailyMaxMins = 480;
            let restMins = parseFloat(pR) * 60; if (isNaN(restMins)) restMins = 0;
            
            let allowedDays = [];
            if (pD && pD.toLowerCase() !== 'clear') allowedDays = pD.split(',').map(n => parseInt(n.trim(), 10) - 1).filter(n => !isNaN(n));
            if (allowedDays.length === 0) allowedDays = [1, 2, 3, 4, 5];

            let shiftTemplates = [
                ...parseShiftList(pP_val).map(s => ({ start: s.start, span: s.span, days: s.days, isPref: hasCustomPref })),
                ...parseShiftList(pC_val).map(s => ({ start: s.start, span: s.span, days: s.days, isPref: false }))
            ];
            if (shiftTemplates.length === 0) continue;

            let dailyMinsArray = Array(7).fill(0);
            let preScheduledMinsTotal = 0;
            
            for (let dayOffset = 0; dayOffset < 7; dayOffset++) {
                let dStr = weekDates[dayOffset];
                let existingEvs = allEvs.filter(e => getStorageKey(e.date) === getStorageKey(dStr) && Number(e.personIdx) === pIdx && !isOffEvent(e));
                let dTotal = existingEvs.reduce((sum, e) => sum + Number(e.duration), 0);
                dailyMinsArray[dayOffset] += dTotal;
                preScheduledMinsTotal += dTotal;
            }

            let weeklyMinsAdj = Math.max(0, weeklyMins - preScheduledMinsTotal);
            if (weeklyMinsAdj <= 0) continue; 

            users.push({ pIdx: pIdx, weeklyMins: weeklyMinsAdj, restMins: restMins, dailyMaxMins: dailyMaxMins, templates: shiftTemplates, allowedDays: allowedDays, dailyMins: dailyMinsArray });
        }

        let intervalCoverage = Array(7).fill(0).map(() => Array(48).fill(0));
        let newEvents = [];
        
        for (let phase = 1; phase <= 2; phase++) {
            let madeAssignment = true;

            while (madeAssignment) {
                await new Promise(resolve => setTimeout(resolve, 0));

                madeAssignment = false;
                let bestGlobalScore = Infinity;
                let bestGlobalUser = null;
                let bestGlobalDay = -1;
                let bestGlobalShift = null;

                for (let u of users) {
                    if (u.weeklyMins <= 0) continue;
                    
                    for (let d = 0; d < 7; d++) {
                        if (u.dailyMins[d] > 0) continue; 
                        
                        let curDate = new Date(startDate); curDate.setDate(curDate.getDate() + d);
                        let dStr = weekDates[d];
                        let dayOfWeek = curDate.getDay();

                        let remDaily = u.dailyMaxMins - u.dailyMins[d];
                        if (remDaily <= 0) continue; 
                        
                        if (!u.allowedDays.includes(dayOfWeek)) continue;
                        
                        let dayEvs = allEvs.filter(e => getStorageKey(e.date) === getStorageKey(dStr) && Number(e.personIdx) === u.pIdx && !isOffEvent(e));
                        let busy = dayEvs.map(e => ({ s: Number(e.startMin), e: Number(e.startMin) + Number(e.duration) }));
                        for (let ne of newEvents) {
                            if (getStorageKey(ne.date) === getStorageKey(dStr) && Number(ne.personIdx) === u.pIdx) {
                                busy.push({ s: Number(ne.startMin), e: Number(ne.startMin) + Number(ne.duration) });
                            }
                        }
                        busy.sort((a,b) => a.s - b.s);

                        let dayOffs = allEvs.filter(e => getStorageKey(e.date) === getStorageKey(dStr) && Number(e.personIdx) === u.pIdx && isOffEvent(e));

                        for (let t of u.templates) {
                            if (phase === 1 && !t.isPref) continue;
                            if (t.days && !t.days.includes(dayOfWeek)) continue;

                            let tStart = t.start;
                            let tEnd = t.start + t.span;
                            
                            let freeGaps = [];
                            let curr = tStart;
                            for (let b of busy) {
                                if (b.s > curr && b.s < tEnd) freeGaps.push({ s: curr, e: Math.min(b.s, tEnd) });
                                if (b.e > curr) curr = b.e;
                            }
                            if (curr < tEnd) freeGaps.push({ s: curr, e: tEnd });

                            for (let gap of freeGaps) {
                                let gapSpan = gap.e - gap.s;
                                if (gapSpan < 60) continue; 
                                
                                let targetPaid = Math.min(u.weeklyMins, remDaily, t.span);
                                let targetSpan = targetPaid + u.restMins;
                                
                                let actualSpan = Math.min(gapSpan, targetSpan);
                                if (actualSpan < 60) continue;

                                let actualPaid = actualSpan > u.restMins ? actualSpan - u.restMins : actualSpan;
                                
                                let maxSlide = gap.e - gap.s - actualSpan;
                                for (let slide = 0; slide <= maxSlide; slide += 30) {
                                    let sStart = gap.s + slide;
                                    
                                    let offOverlap = 0;
                                    for (let off of dayOffs) {
                                        let overlapS = Math.max(sStart, Number(off.startMin));
                                        let overlapE = Math.min(sStart + actualSpan, Number(off.startMin) + Number(off.duration));
                                        if (overlapS < overlapE) {
                                            offOverlap += (overlapE - overlapS);
                                        }
                                    }
                                    
                                    let expectedYield = actualPaid - offOverlap;
                                    if (expectedYield <= 0) continue;

                                    let penalty = 0;
                                    let sIdx = Math.floor(sStart / 30);
                                    let eIdx = Math.floor((sStart + actualSpan) / 30);
                                    
                                    for (let idx = sIdx; idx < eIdx && idx < 48; idx++) {
                                        let c = intervalCoverage[d][idx];
                                        if (c === 0) penalty -= 10000;
                                        else if (c === 1) penalty -= 1000;
                                        else if (c === 2) penalty -= 100;
                                        else penalty -= 10; 
                                    }
                                    
                                    if (t.isPref) penalty -= 500; 
                                    penalty -= (expectedYield * 1000); 
                                    
                                    let score = penalty - (u.weeklyMins * 100);

                                    if (score < bestGlobalScore) {
                                        bestGlobalScore = score; bestGlobalUser = u; bestGlobalDay = d;
                                        bestGlobalShift = { start: sStart, span: actualSpan, paid: expectedYield };
                                    }
                                }
                            }
                        }
                    }
                } 

                if (bestGlobalUser && bestGlobalShift) {
                    let dStr = weekDates[bestGlobalDay];
                    
                    let newEv = {
                        id: crypto.randomUUID ? crypto.randomUUID() : (Date.now().toString() + Math.random()),
                        title: activeMode,
                        startMin: bestGlobalShift.start,
                        duration: bestGlobalShift.span,
                        color: colors[bestGlobalUser.pIdx % colors.length],
                        date: dStr,
                        personIdx: bestGlobalUser.pIdx
                    };
                    
                    newEvents.push(newEv);
                    events.push(newEv); 
                    
                    let sIdx = Math.floor(bestGlobalShift.start / 30);
                    let eIdx = Math.floor((bestGlobalShift.start + bestGlobalShift.span) / 30);
                    for (let idx = sIdx; idx < eIdx && idx < 48; idx++) intervalCoverage[bestGlobalDay][idx]++;

                    bestGlobalUser.weeklyMins -= bestGlobalShift.paid;
                    bestGlobalUser.dailyMins[bestGlobalDay] += bestGlobalShift.paid;
                    madeAssignment = true;
                }
            } 
        } 

        let latestAllEvs = typeof unrollEvents === 'function' ? unrollEvents() : events;
        let finalEventsList = [];

        for (let ev of events) {
            if (weekDates.includes(ev.date) && !isOffEvent(ev)) {
                let dailyOffs = latestAllEvs.filter(e => 
                    getStorageKey(e.date) === getStorageKey(ev.date) && 
                    Number(e.personIdx) === Number(ev.personIdx) && 
                    isOffEvent(e)
                );
                if (dailyOffs.length > 0) {
                    qToUpdate.add(getStorageKey(ev.date));
                    let validChunks = findAvailableSlots(ev.startMin, Number(ev.startMin) + Number(ev.duration), dailyOffs);
                    validChunks.forEach(chunk => {
                        if (chunk.end - chunk.start > 0) {
                            finalEventsList.push({
                                ...ev,
                                id: crypto.randomUUID ? crypto.randomUUID() : (Date.now().toString() + Math.random()),
                                startMin: chunk.start,
                                duration: chunk.end - chunk.start
                            });
                        }
                    });
                } else {
                    finalEventsList.push(ev);
                }
            } else {
                finalEventsList.push(ev);
            }
        }
        events = finalEventsList;

        for (let e of newEvents) {
            if (e && e.date) qToUpdate.add(getStorageKey(e.date));
        }
        
        await Promise.all(Array.from(qToUpdate).map(async (q) => {
            let dayEvents = events.filter(e => getStorageKey(e.date) === q);
            if (dayEvents.length === 0) {
                await fetch(`${CALDAV_URL}${q}.json`, {
                    method: 'DELETE',
                    headers: { 'Authorization': getAuthHeader() }
                });
            } else {
                let response = await fetch(`${CALDAV_URL}${q}.json`, { 
                    method: 'PUT', 
                    headers: { 'Content-Type': 'application/json', 'Authorization': getAuthHeader() }, 
                    body: JSON.stringify(dayEvents) 
                });
                if (!response.ok) throw new Error(`HTTP Error ${response.status} when saving ${q}`);
            }
        }));

        render(); 
        let modal = document.getElementById('schedulingModal');
        if (modal) modal.style.display = 'none';

    } catch (err) {
        console.error("MakeSchedule Error:", err);
        alert("An error occurred while generating or saving the schedule. Please check the console for details.");
    } finally { 
        window.isScheduling = false; 
    }
});
