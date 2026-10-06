// The APK is served by the developer's own server, which is not always online.
// The page works either way: online -> live version + download; offline -> a clear message.
var SERVER = 'https://attendance.example.com';
var PLAY_URL = ''; // set to the Play listing URL once live, 

function $(id){return document.getElementById(id);}
function esc(s){var d=document.createElement('div');d.textContent=String(s);return d.innerHTML;}

if (PLAY_URL) { var p=$('play-btn'); p.href=PLAY_URL; p.hidden=false; }

function showOffline(){
  $('status').textContent = '\u25CF Download server offline'; $('status').className = 'status off';
  $('ver').textContent = ''; $('changelog-card').hidden = true;
  $('offline').hidden = false;
  var b = $('dl'); b.textContent = 'Download unavailable right now';
  b.classList.add('disabled'); b.setAttribute('aria-disabled','true'); b.removeAttribute('href');
}

var ctl = window.AbortController ? new AbortController() : null;
var timer = setTimeout(function(){ if (ctl) ctl.abort(); else showOffline(); }, 4000);

fetch(SERVER + '/api/app/version?version_code=0', {signal: ctl ? ctl.signal : undefined, cache:'no-store'})
  .then(function(r){ if(!r.ok) throw new Error('bad status'); return r.json(); })
  .then(function(d){
    clearTimeout(timer);
    if(!d.ok || !d.apk_url) throw new Error('no apk');
    // Build everything that can throw BEFORE touching the page, so it never half-updates.
    var notes = Array.isArray(d.notes) && d.notes.length ? d.notes.slice(0,12) : ['No release notes recorded.'];
    var items = notes.map(function(n){return '<li>'+esc(n)+'</li>';}).join('');
    var label = 'v' + d.latest_version_name + ' (build ' + d.latest_version_code + ')';
    $('changelog').innerHTML = items; $('changelog-card').hidden = false;
    $('ver').textContent = label;
    var b = $('dl'); b.href = SERVER + d.apk_url; b.textContent = '\u2B07 Download ' + label;
    b.classList.remove('disabled'); b.removeAttribute('aria-disabled');
    $('status').textContent = '\u25CF Server online'; $('status').className = 'status on';
  })
  .catch(function(){ clearTimeout(timer); showOffline(); });
