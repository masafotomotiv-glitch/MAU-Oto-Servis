(function(){
"use strict";

const AUTH_KEY="mau_auth_v2";
const LEGACY_PASSWORD_KEY="mau_kullanici_sifresi";
const PROFILE_KEY="mau_hesabim";
const FAIL_KEY="mau_auth_fail_v2";
const SESSION_KEY="mau_session_v2";
const ITERATIONS=210000;
const SESSION_MAX_MS=12*60*60*1000;
const IDLE_MAX_MS=60*60*1000;
const CIPHER_PREFIX="AKS1.";

const rawGet=Storage.prototype.getItem;
const rawSet=Storage.prototype.setItem;
const rawRemove=Storage.prototype.removeItem;
const rawKey=Storage.prototype.key;

function isLocalStorage(obj){return obj===window.localStorage}
function nativeVault(){
  try{
    return !!(window.MAUSecurity &&
      typeof window.MAUSecurity.encrypt==="function" &&
      typeof window.MAUSecurity.decrypt==="function");
  }catch(e){return false}
}
function sensitiveKey(k){return /^mau_/i.test(String(k||""))}

function migrateAtRest(){
  if(!nativeVault())return;
  try{
    const keys=[];
    for(let i=0;i<localStorage.length;i++){
      const k=rawKey.call(localStorage,i);
      if(k&&sensitiveKey(k))keys.push(k);
    }
    keys.forEach(k=>{
      const v=rawGet.call(localStorage,k);
      if(v!=null&&!String(v).startsWith(CIPHER_PREFIX)){
        const enc=window.MAUSecurity.encrypt(String(v));
        if(typeof enc==="string"&&enc.startsWith(CIPHER_PREFIX)){
          rawSet.call(localStorage,k,enc);
        }
      }
    });
  }catch(e){console.warn("MAU secure-storage migration:",e)}
}

migrateAtRest();

Storage.prototype.getItem=function(k){
  const v=rawGet.call(this,k);
  if(v==null)return null;
  if(isLocalStorage(this)&&sensitiveKey(k)&&String(v).startsWith(CIPHER_PREFIX)){
    if(!nativeVault())return null;
    try{
      const out=window.MAUSecurity.decrypt(String(v));
      return typeof out==="string"?out:null;
    }catch(e){return null}
  }
  if(isLocalStorage(this)&&sensitiveKey(k)&&nativeVault()){
    try{
      const enc=window.MAUSecurity.encrypt(String(v));
      if(typeof enc==="string"&&enc.startsWith(CIPHER_PREFIX))rawSet.call(this,k,enc);
    }catch(e){}
  }
  return v;
};
Storage.prototype.setItem=function(k,v){
  if(isLocalStorage(this)&&sensitiveKey(k)&&nativeVault()){
    try{
      const enc=window.MAUSecurity.encrypt(String(v));
      if(typeof enc==="string"&&enc.startsWith(CIPHER_PREFIX)){
        return rawSet.call(this,k,enc);
      }
    }catch(e){}
  }
  return rawSet.call(this,k,String(v));
};

function loadJson(storage,key,def){
  try{
    const v=JSON.parse(storage.getItem(key)||"");
    return v&&typeof v==="object"?v:def;
  }catch(e){return def}
}
function saveJson(storage,key,v){storage.setItem(key,JSON.stringify(v))}
function bytesToB64(bytes){
  let s="";for(let i=0;i<bytes.length;i++)s+=String.fromCharCode(bytes[i]);
  return btoa(s);
}
function b64ToBytes(s){
  const bin=atob(String(s||"")),out=new Uint8Array(bin.length);
  for(let i=0;i<bin.length;i++)out[i]=bin.charCodeAt(i);
  return out;
}
function randomB64(n){
  const b=new Uint8Array(n);crypto.getRandomValues(b);return bytesToB64(b);
}
async function derive(password,saltB64,iterations){
  if(!window.crypto||!crypto.subtle)throw new Error("Güvenli şifre altyapısı bu cihazda kullanılamıyor.");
  const enc=new TextEncoder();
  const base=await crypto.subtle.importKey("raw",enc.encode(String(password)),"PBKDF2",false,["deriveBits"]);
  const bits=await crypto.subtle.deriveBits(
    {name:"PBKDF2",salt:b64ToBytes(saltB64),iterations:Number(iterations||ITERATIONS),hash:"SHA-256"},
    base,256
  );
  return bytesToB64(new Uint8Array(bits));
}
function safeEqual(a,b){
  a=String(a||"");b=String(b||"");
  if(a.length!==b.length)return false;
  let x=0;for(let i=0;i<a.length;i++)x|=a.charCodeAt(i)^b.charCodeAt(i);
  return x===0;
}
function profileUsername(){
  const p=loadJson(localStorage,PROFILE_KEY,{});
  return String(p.kullaniciAdi||"admin").trim()||"admin";
}
async function storePasswordHash(username,password,allowShort){
  const pass=String(password||"");
  if(!allowShort&&pass.length<8)throw new Error("Şifre en az 8 karakter olmalı.");
  const salt=randomB64(16);
  const hash=await derive(pass,salt,ITERATIONS);
  const rec={v:2,username:String(username||"admin").trim()||"admin",salt,hash,iterations:ITERATIONS,updatedAt:new Date().toISOString()};
  saveJson(localStorage,AUTH_KEY,rec);
  localStorage.removeItem(LEGACY_PASSWORD_KEY);
  localStorage.removeItem(FAIL_KEY);
  return rec;
}
async function prepare(){
  let auth=loadJson(localStorage,AUTH_KEY,null);
  if(auth&&auth.hash&&auth.salt)return {configured:true,migrated:false,username:auth.username||profileUsername()};
  const legacy=localStorage.getItem(LEGACY_PASSWORD_KEY);
  if(legacy){
    auth=await storePasswordHash(profileUsername(),legacy,true);
    return {configured:true,migrated:true,username:auth.username};
  }
  return {configured:false,migrated:false,username:profileUsername()};
}
function lockState(){
  const f=loadJson(localStorage,FAIL_KEY,{count:0,lockUntil:0});
  const left=Math.max(0,Number(f.lockUntil||0)-Date.now());
  return {count:Number(f.count||0),locked:left>0,msLeft:left};
}
function clearFailures(){localStorage.removeItem(FAIL_KEY)}
function recordFailure(){
  const f=loadJson(localStorage,FAIL_KEY,{count:0,lockUntil:0});
  f.count=Number(f.count||0)+1;
  if(f.count>=5){
    const sec=Math.min(15*60,30*Math.pow(2,Math.min(5,f.count-5)));
    f.lockUntil=Date.now()+sec*1000;
  }else f.lockUntil=0;
  saveJson(localStorage,FAIL_KEY,f);
  return lockState();
}
function newSession(username){
  const now=Date.now();
  const s={v:2,username:String(username||""),createdAt:now,lastActivity:now,expiresAt:now+SESSION_MAX_MS,nonce:randomB64(18)};
  saveJson(sessionStorage,SESSION_KEY,s);
  return s;
}
function session(){
  const s=loadJson(sessionStorage,SESSION_KEY,null);
  if(!s)return null;
  const now=Date.now();
  if(!s.expiresAt||now>Number(s.expiresAt)||!s.lastActivity||now-Number(s.lastActivity)>IDLE_MAX_MS){
    sessionStorage.removeItem(SESSION_KEY);return null;
  }
  return s;
}
function isAuthenticated(){return !!session()}
function touch(){
  const s=session();if(!s)return false;
  s.lastActivity=Date.now();saveJson(sessionStorage,SESSION_KEY,s);return true;
}
async function login(username,password){
  const p=await prepare();
  if(!p.configured)throw new Error("İlk güvenlik kurulumu gerekli.");
  const l=lockState();
  if(l.locked)throw new Error("Çok fazla hatalı deneme. "+Math.ceil(l.msLeft/1000)+" saniye sonra tekrar deneyin.");
  const auth=loadJson(localStorage,AUTH_KEY,null);
  const user=String(username||"").trim();
  if(!auth||user.toLocaleLowerCase("tr-TR")!==String(auth.username||"").toLocaleLowerCase("tr-TR")){
    recordFailure();throw new Error("Kullanıcı adı veya şifre hatalı.");
  }
  const hash=await derive(String(password||""),auth.salt,auth.iterations);
  if(!safeEqual(hash,auth.hash)){
    const n=recordFailure();
    if(n.locked)throw new Error("Çok fazla hatalı deneme. "+Math.ceil(n.msLeft/1000)+" saniye sonra tekrar deneyin.");
    throw new Error("Kullanıcı adı veya şifre hatalı.");
  }
  clearFailures();newSession(auth.username);return true;
}
async function setup(username,password){
  const p=await prepare();
  if(p.configured)throw new Error("Güvenlik kurulumu zaten yapılmış.");
  const rec=await storePasswordHash(username,password,false);
  newSession(rec.username);return true;
}
async function setPassword(username,password){
  if(!isAuthenticated())throw new Error("Şifre değiştirmek için tekrar giriş yapın.");
  const rec=await storePasswordHash(username||profileUsername(),password,false);
  newSession(rec.username);return true;
}
function updateUsername(username){
  if(!isAuthenticated())throw new Error("Kullanıcı adını değiştirmek için giriş yapın.");
  const auth=loadJson(localStorage,AUTH_KEY,null);
  if(!auth||!auth.hash)throw new Error("Güvenlik kaydı bulunamadı.");
  auth.username=String(username||"").trim()||"admin";
  auth.updatedAt=new Date().toISOString();
  saveJson(localStorage,AUTH_KEY,auth);
  const s=session();if(s){s.username=auth.username;saveJson(sessionStorage,SESSION_KEY,s)}
  return auth.username;
}
function logout(){
  sessionStorage.removeItem(SESSION_KEY);
}
function nextUrl(){
  const q=new URLSearchParams(location.search);
  const n=String(q.get("next")||"index.html");
  if(!/^[A-Za-z0-9_.-]+\.html(?:[?#].*)?$/.test(n))return "index.html";
  if(/^(Giris|Cikis)\.html/i.test(n))return "index.html";
  return n;
}
function pageName(){return (location.pathname.split("/").pop()||"index.html").split("?")[0]}
function publicPage(){return /^(Giris|Cikis)\.html$/i.test(pageName())}
function reveal(){document.documentElement.style.visibility=""}
function gate(){
  if(publicPage()){reveal();return}
  if(isAuthenticated()){touch();reveal();return}
  const n=encodeURIComponent(pageName()+location.search+location.hash);
  location.replace("Giris.html?next="+n);
}

document.documentElement.style.visibility="hidden";
let lastTouch=0;
["click","keydown","touchstart","pointerdown"].forEach(ev=>document.addEventListener(ev,function(){
  const now=Date.now();if(now-lastTouch>30000){lastTouch=now;touch()}
},{passive:true,capture:true}));
document.addEventListener("visibilitychange",function(){if(document.visibilityState==="visible"&&!publicPage()&&!isAuthenticated())gate()});
gate();

window.MAUAuth={
  prepare,login,setup,setPassword,updateUsername,logout,isAuthenticated,touch,session,lockState,nextUrl,
  securityInfo:function(){return {password:"PBKDF2-SHA256",iterations:ITERATIONS,nativeAtRest:nativeVault(),sessionMaxHours:12,idleMinutes:60}}
};
})();