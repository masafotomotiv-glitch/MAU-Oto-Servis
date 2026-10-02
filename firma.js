(function(){
"use strict";
const KEY="mau_ayarlar";
function esc(v){return String(v==null?"":v).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}
function get(){
 const d={firmaAdi:"",telefon:"",eposta:"",vergiDairesi:"",vergiNo:"",adres:"",logoData:""};
 try{return Object.assign(d,JSON.parse(localStorage.getItem(KEY)||"{}")||{})}catch(e){return d}
}
function infoLines(s){
 const rows=[];
 if(s.adres)rows.push(esc(s.adres));
 const contact=[];if(s.telefon)contact.push("Tel: "+esc(s.telefon));if(s.eposta)contact.push("E-posta: "+esc(s.eposta));if(contact.length)rows.push(contact.join(" · "));
 const tax=[];if(s.vergiDairesi)tax.push("Vergi Dairesi: "+esc(s.vergiDairesi));if(s.vergiNo)tax.push("Vergi No: "+esc(s.vergiNo));if(tax.length)rows.push(tax.join(" · "));
 return rows;
}
function header(title,meta){
 const s=get(),name=esc(s.firmaAdi||"Firma");
 const logo=s.logoData?'<img class="mau-doc-logo" src="'+s.logoData+'" alt="'+name+' logosu">':'';
 const lines=infoLines(s).map(x=>'<div>'+x+'</div>').join("");
 return '<div class="mau-doc-company">'+
   '<div class="mau-doc-company-left">'+logo+'<div><div class="mau-doc-company-name">'+name+'</div><div class="mau-doc-company-info">'+lines+'</div></div></div>'+
   '<div class="mau-doc-company-right"><div class="mau-doc-title">'+esc(title||"Rapor")+'</div>'+(meta?'<div class="mau-doc-meta">'+esc(meta)+'</div>':'')+'</div>'+
 '</div>';
}
function footer(){
 const s=get(),name=esc(s.firmaAdi||"Firma");
 return '<div class="mau-doc-footer">'+name+' · '+new Date().toLocaleString("tr-TR")+'</div>';
}
function apply(target,title,meta){
 const el=typeof target==="string"?document.getElementById(target):target;
 if(el)el.innerHTML=header(title,meta);
 return el;
}
function injectStyle(){
 if(document.getElementById("mauFirmaStyle"))return;
 const st=document.createElement("style");st.id="mauFirmaStyle";st.textContent=
 '.mau-doc-company{display:flex;justify-content:space-between;gap:18px;align-items:flex-start;border-bottom:2px solid #173b62;padding:10px 0 12px;margin:0 0 14px;font-family:Arial,sans-serif;color:#18324d}'+
 '.mau-doc-company-left{display:flex;gap:12px;align-items:flex-start;min-width:0}.mau-doc-logo{max-width:150px;max-height:70px;object-fit:contain;display:block}.mau-doc-company-name{font-size:20px;font-weight:900;line-height:1.2}.mau-doc-company-info{font-size:10px;line-height:1.55;margin-top:5px;color:#485b70}.mau-doc-company-right{text-align:right;min-width:170px}.mau-doc-title{font-size:21px;font-weight:900;color:#18324d}.mau-doc-meta{font-size:10px;color:#64748b;margin-top:5px;line-height:1.4}.mau-doc-footer{margin-top:18px;padding-top:8px;border-top:1px solid #dfe5eb;font-size:9px;color:#6f8094;text-align:center}'+
 '@media(max-width:600px){.mau-doc-company{display:block}.mau-doc-company-right{text-align:left;margin-top:10px}.mau-doc-logo{max-width:120px}}'+
 '@media print{.mau-doc-company{break-inside:avoid}.mau-doc-company-info,.mau-doc-meta,.mau-doc-footer{color:#333!important}}';
 document.head.appendChild(st);
}
injectStyle();
window.MAUFirma={get:get,esc:esc,header:header,footer:footer,apply:apply,refreshStyle:injectStyle};
})();