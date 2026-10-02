(function(){
"use strict";
const APP_ID="mau-oto-servis";
const APP_VERSION="2026.10";
const STATE_KEY="mau_lisans";
const DEVICE_KEY="mau_lisans_cihaz";
const CONFIG_KEY="mau_lisans_config";
const BUILD={mode:"development",verifyUrl:"",graceDays:7,warningDays:7};

function loadObj(k){try{const v=JSON.parse(localStorage.getItem(k)||"{}");return v&&typeof v==="object"&&!Array.isArray(v)?v:{}}catch(e){return{}}}
function saveObj(k,v){localStorage.setItem(k,JSON.stringify(v))}
function config(){
 const local=loadObj(CONFIG_KEY);
 if(BUILD.mode==="production")return Object.assign({},BUILD);
 return Object.assign({},BUILD,local)
}
function uid(){return "MAU-"+Date.now().toString(36).toUpperCase()+"-"+Math.random().toString(36).slice(2,10).toUpperCase()}
function deviceId(){let v=localStorage.getItem(DEVICE_KEY);if(!v){v=uid();localStorage.setItem(DEVICE_KEY,v)}return v}
function state(){return Object.assign({status:"development",licenseKey:"",plan:"",customer:"",validUntil:"",lastVerifiedAt:"",lastSeenAt:"",token:""},loadObj(STATE_KEY))}
function persist(s){saveObj(STATE_KEY,s)}
function now(){return Date.now()}
function parseDate(v){const t=Date.parse(v||"");return Number.isFinite(t)?t:0}
function daysLeft(v){if(!v)return null;return Math.ceil((parseDate(v)-now())/86400000)}
function isLicensePage(){return /(?:^|\/)Lisans\.html(?:$|[?#])/i.test(location.href)}
function isBackupPage(){return /(?:^|\/)Yedekleme\.html(?:$|[?#])/i.test(location.href)}

function assess(){
 const cfg=config(),s=state(),n=now();
 if(cfg.mode!=="production")return {mode:"development",write:true,active:true,status:"development",daysLeft:null,state:s,config:cfg};
 const exp=parseDate(s.validUntil),verified=parseDate(s.lastVerifiedAt),seen=parseDate(s.lastSeenAt);
 const rollback=seen&&n+10*60000<seen;
 const graceMs=Math.max(1,Number(cfg.graceDays||7))*86400000;
 const verificationFresh=verified&&n-verified<=graceMs;
 const notExpired=exp&&n<=exp;
 let active=!!(s.licenseKey&&s.status==="active"&&notExpired&&verificationFresh&&!rollback);
 let reason="";
 if(!s.licenseKey)reason="Lisans anahtarı yok.";
 else if(rollback)reason="Cihaz tarihi geriye alınmış görünüyor. İnternet doğrulaması gerekli.";
 else if(!notExpired)reason="Lisans süresi dolmuş.";
 else if(!verificationFresh)reason="Çevrimdışı doğrulama süresi dolmuş. İnternet doğrulaması gerekli.";
 else if(s.status!=="active")reason="Lisans aktif değil.";
 return {mode:"production",write:active,active,status:active?"active":"readonly",daysLeft:daysLeft(s.validUntil),reason,state:s,config:cfg}
}

function safeLicenseKey(k){return k===STATE_KEY||k===DEVICE_KEY||k===CONFIG_KEY||String(k||"").startsWith("mau_lisans_")}
let readonlyPatched=false;
function patchReadonlyStorage(){
 if(readonlyPatched)return;readonlyPatched=true;
 const set=Storage.prototype.setItem,remove=Storage.prototype.removeItem,clear=Storage.prototype.clear;
 Storage.prototype.setItem=function(k,v){
  if(this===localStorage&&String(k||"").startsWith("mau_")&&!safeLicenseKey(k)){throw new Error("MAU_LICENSE_READONLY")}
  return set.call(this,k,v)
 };
 Storage.prototype.removeItem=function(k){
  if(this===localStorage&&String(k||"").startsWith("mau_")&&!safeLicenseKey(k)){throw new Error("MAU_LICENSE_READONLY")}
  return remove.call(this,k)
 };
 Storage.prototype.clear=function(){throw new Error("MAU_LICENSE_READONLY")};
 window.addEventListener("error",function(e){
  if(String(e.message||"").includes("MAU_LICENSE_READONLY")){e.preventDefault();showLockNotice()}
 })
}

function banner(text,type){
 let b=document.getElementById("mauLicenseBanner");
 if(!b){b=document.createElement("div");b.id="mauLicenseBanner";b.style.cssText="position:sticky;top:0;z-index:99999;padding:9px 12px;font:700 11px Arial;text-align:center;box-shadow:0 2px 7px #0002;cursor:pointer";b.onclick=()=>location.href="Lisans.html";document.body.insertBefore(b,document.body.firstChild)}
 b.style.background=type==="warn"?"#fff2c8":type==="stop"?"#ffe4e8":"#e8f7ef";b.style.color=type==="stop"?"#9d1f31":"#5b4700";b.textContent=text
}
function showLockNotice(){
 if(isLicensePage())return;
 const old=document.getElementById("mauLicenseLock");
 if(old)return;
 const d=document.createElement("div");d.id="mauLicenseLock";d.style.cssText="position:fixed;inset:0;background:#0008;z-index:100000;display:flex;align-items:center;justify-content:center;padding:18px";
 d.innerHTML='<div style="max-width:430px;background:white;border-radius:14px;padding:20px;font-family:Arial;color:#18324d;text-align:center"><div style="font-size:42px">🔒</div><h2 style="margin:8px 0">Lisans Süresi Doldu</h2><p style="font-size:12px;line-height:1.6;color:#66778a">Verileriniz silinmedi. Kayıtlarınızı görüntüleyebilir ve yedek alabilirsiniz. Yeni kayıt ve düzenleme için lisansı yenileyin.</p><div style="display:grid;grid-template-columns:1fr 1fr;gap:8px"><button id="mauGoLicense" style="border:0;border-radius:9px;padding:12px;background:#0b76c5;color:white;font-weight:800">Lisansı Yenile</button><button id="mauCloseLicense" style="border:0;border-radius:9px;padding:12px;background:#eef2f6;color:#405168;font-weight:800">Görüntülemeye Dön</button></div></div>';
 document.body.appendChild(d);d.querySelector("#mauGoLicense").onclick=()=>location.href="Lisans.html";d.querySelector("#mauCloseLicense").onclick=()=>d.remove()
}

async function verifyOnline(licenseKey){
 const cfg=config();if(!cfg.verifyUrl)throw new Error("Lisans sunucu adresi tanımlı değil.");
 const body={appId:APP_ID,appVersion:APP_VERSION,licenseKey:String(licenseKey||"").trim(),deviceId:deviceId()};
 const r=await fetch(cfg.verifyUrl,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body),cache:"no-store"});
 if(!r.ok)throw new Error("Lisans sunucusu yanıt vermedi ("+r.status+").");
 const data=await r.json();
 if(!data||data.ok!==true||data.status!=="active")throw new Error((data&&data.message)||"Lisans aktif değil.");
 const s=state();s.licenseKey=body.licenseKey;s.status="active";s.plan=data.plan||"";s.customer=data.customer||"";s.licenseId=data.licenseId||"";s.validUntil=data.validUntil||"";s.lastVerifiedAt=data.serverTime||new Date().toISOString();s.lastSeenAt=s.lastVerifiedAt;s.token=data.token||"";persist(s);return s
}
function activateDevelopment(days){
 const cfg=config();if(cfg.mode==="production")throw new Error("Üretim modunda test lisansı oluşturulamaz.");
 const s=state(),d=new Date();d.setDate(d.getDate()+Number(days||30));s.licenseKey=s.licenseKey||"DEV-"+deviceId().slice(-8);s.status="active";s.plan="Geliştirme";s.customer=s.customer||"MAU Test";s.validUntil=d.toISOString();s.lastVerifiedAt=new Date().toISOString();s.lastSeenAt=s.lastVerifiedAt;persist(s);return s
}
function setConfig(next){const c=Object.assign({},config(),next||{});saveObj(CONFIG_KEY,c);return c}
function touch(){
 const a=assess(),s=a.state;s.lastSeenAt=new Date().toISOString();try{persist(s)}catch(e){}
 if(a.mode==="development")return
 if(!a.active){
   patchReadonlyStorage();banner("Lisans pasif · Veriler silinmedi · Sistem salt okunur modda","stop");
   if(!isLicensePage()&&!isBackupPage())setTimeout(showLockNotice,250)
 }else if(a.daysLeft!=null&&a.daysLeft<=Number(a.config.warningDays||7)){
   banner("Lisans bitimine "+Math.max(0,a.daysLeft)+" gün kaldı · Yenilemek için dokunun","warn")
 }
}
document.addEventListener("DOMContentLoaded",touch);

window.MAULicense={
 appId:APP_ID,version:APP_VERSION,state,config,assess,deviceId,verifyOnline,activateDevelopment,setConfig,
 canWrite:function(){return assess().write},
 requireWrite:function(){if(assess().write)return true;showLockNotice();return false},
 statusText:function(){const a=assess();if(a.mode==="development")return"Geliştirme";if(a.active)return"Aktif";return"Salt Okunur"},
 privacyPayload:function(){return {appId:APP_ID,appVersion:APP_VERSION,licenseKey:state().licenseKey,deviceId:deviceId()}}
};
})();