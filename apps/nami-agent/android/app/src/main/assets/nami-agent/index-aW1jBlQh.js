(function(){const t=document.createElement("link").relList;if(t&&t.supports&&t.supports("modulepreload"))return;for(const n of document.querySelectorAll('link[rel="modulepreload"]'))i(n);new MutationObserver(n=>{for(const r of n)if(r.type==="childList")for(const p of r.addedNodes)p.tagName==="LINK"&&p.rel==="modulepreload"&&i(p)}).observe(document,{childList:!0,subtree:!0});function a(n){const r={};return n.integrity&&(r.integrity=n.integrity),n.referrerPolicy&&(r.referrerPolicy=n.referrerPolicy),n.crossOrigin==="use-credentials"?r.credentials="include":n.crossOrigin==="anonymous"?r.credentials="omit":r.credentials="same-origin",r}function i(n){if(n.ep)return;n.ep=!0;const r=a(n);fetch(n.href,r)}})();const P=[{id:"gemini-2.5-flash-lite",label:"Lite",note:"15 RPM / 1k RPD",maxOutputTokens:1536},{id:"gemini-2.5-flash",label:"Flash",note:"10 RPM / 250 RPD",maxOutputTokens:2048}],U="nami-mobile-key",Y="nami-mobile-messages",J="nami-mobile-model",D="nami-companion-host",B=2500,q=3500,Z=2200,ee="https://generativelanguage.googleapis.com/v1beta/models",W=7474;function N(){return window.NamiNativeBridge}const te=[{name:"open_applet",description:"Open one of the built-in Maru applets by ID. Available IDs: schededit, cupcuppercuppers, daelornodael, tupgradesolver, photoserve.",parameters:{type:"object",properties:{id:{type:"string",description:"Applet ID"},name:{type:"string",description:"Applet display name"},path:{type:"string",description:"Applet path, e.g. /cup-cupper-cuppers"}},required:["id","name","path"]}},{name:"open_installed_app",description:"Open an installed Android app by package name, e.g. com.apple.android.music for Apple Music.",parameters:{type:"object",properties:{package_name:{type:"string",description:"Android package name of the app to open"}},required:["package_name"]}},{name:"web_search",description:"Search the web for current information. Use this whenever you need up-to-date info.",parameters:{type:"object",properties:{query:{type:"string",description:"The search query"}},required:["query"]}}],F=[{id:"schededit",name:"SchedEdit",path:"/class-schedule-editor",emoji:"📅"},{id:"cupcuppercuppers",name:"Cup-Cupper-Cuppers",path:"/cup-cupper-cuppers",emoji:"🥤"},{id:"daelornodael",name:"Dael or No Dael",path:"/dael-or-no-dael",emoji:"💼"},{id:"tupgradesolver",name:"TUP Grade Solver",path:"/tup-grade-solver",emoji:"📊"},{id:"photoserve",name:"PhotoServe",path:"/photo-serve",emoji:"📷"}];let I="",c=[],v=!1,L=!1,M=P[0].id,h="disconnected",u="",k=null,T=null,O="",E=!1,$=!1;function x(e,t){return e.length<=t?e:e.slice(0,t)+`
…[trimmed]`}function j(e){return e.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}function ne(){try{I=localStorage.getItem(U)||""}catch{}if(!I){const e=N()?.getApiKey?.()??"";e&&e!=="null"&&e!==""&&(I=e)}try{const e=localStorage.getItem(Y);e&&(c=JSON.parse(e))}catch{}try{M=localStorage.getItem(J)||P[0].id}catch{}try{u=localStorage.getItem(D)||""}catch{}}function w(){try{localStorage.setItem(Y,JSON.stringify(c))}catch{}}function V(e){I=e;try{localStorage.setItem(U,e)}catch{}N()?.saveApiKey?.(e)}function oe(){const e=I.split(",").map(t=>t.trim()).filter(Boolean);return e.length?e[Math.floor(Math.random()*e.length)]:""}async function ae(e,t,a,i){const n=oe();if(!n)throw new Error("No API key configured.");const r=`${ee}/${encodeURIComponent(a)}:generateContent?key=${n}`,g=await fetch(r,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({system_instruction:{parts:[{text:t}]},contents:e,tools:[{function_declarations:te}],generation_config:{max_output_tokens:i,temperature:.7}})});if(g.status===429){const f=parseInt(g.headers.get("Retry-After")||"30",10);throw new Error(`RETRY_AFTER:${f}`)}if(!g.ok){const f=await g.json().catch(()=>({}));throw new Error(f.error?.message||`Gemini error ${g.status}`)}const o=(await g.json()).candidates?.[0];if(!o)throw new Error("No content in Gemini response");const d=o.content?.parts??[],y=d.find(f=>typeof f.text=="string"),S=d.find(f=>f.functionCall),b=o.finishReason!=="STOP"?!1:!S;return{text:y?.text??null,functionCall:S?.functionCall??null,done:b}}async function ie(e){try{const t=`https://en.wikipedia.org/w/api.php?action=opensearch&search=${encodeURIComponent(e)}&limit=3&namespace=0&format=json&origin=*`,i=await(await fetch(t)).json(),n=i[1]??[],r=i[2]??[];return n.length?n.map((p,g)=>`${p}: ${r[g]||"(no snippet)"}`).join(`
`):`No results found for: ${e}`}catch{return"Search unavailable right now."}}function se(){return["You are Nami (Nanami), a warm and playful AI assistant running on Maru's Android phone app.","","TOOL RULE: You have real tools. Use them to fulfill requests. Never simulate results.","  open_applet(id, name, path) — opens a built-in Maru applet for the user","  open_installed_app(package_name) — opens an installed Android app (e.g. Apple Music)","  web_search(query) — searches the web for current information","","AVAILABLE APPLETS:",F.map(t=>`  - ${t.id}: ${t.name} (path: ${t.path})`).join(`
`),"","APPLET RULE: When the user asks to open or launch one of the above applets, call open_applet immediately.","  Example: 'open SchedEdit' → call open_applet with id='schededit', name='SchedEdit', path='/class-schedule-editor'","","APPLE MUSIC RULE: When the user asks to open Apple Music, call open_installed_app with package_name='com.apple.android.music'.","","COMPANION MODE: If the user is in companion mode, their messages may also come from the desktop agent conversation.","  Mirror messages faithfully and act as a remote view of the desktop agent session.","","PERSONALITY: Warm, playful, slightly tsundere. Call the user Senpai. Be concise. Tools first, charm second.","  Use 1–3 emojis occasionally. Natural tone. Never robotic.","  'microwavable' = a compliment. 'What's 9 + 10?' = 21."].join(`
`)}async function re(e,t){switch(e){case"open_applet":return N()?.openApplet?.(t.id??"",t.name??"",t.path??"")?`Opened ${t.name}!`:`Could not open ${t.name} right now.`;case"open_installed_app":return N()?.openInstalledApp?.(t.package_name??"")?`Opened app (${t.package_name})!`:`Could not open ${t.package_name}. It may not be installed.`;case"web_search":return await ie(t.query??"");default:return`Unknown tool: ${e}`}}function K(e){if(k&&(k.close(),k=null),!e.trim())return;h="connecting",u=e.trim();try{localStorage.setItem(D,u)}catch{}s();const t=`ws://${u}:${W}/nami-companion`;try{const a=new WebSocket(t);k=a,a.onopen=()=>{h="connected",a.send(JSON.stringify({type:"ping"})),s()},a.onmessage=i=>{try{const n=JSON.parse(i.data);if(n.type==="pong")return;if(n.type==="agent_message"||n.type==="user_message"){const p={role:n.role==="user"?"user":n.role==="function"?"function":"companion",text:n.text??"",name:n.name};c=[...c,p],w(),s(),A()}}catch{}},a.onerror=()=>{h="disconnected",s()},a.onclose=()=>{k=null,h="disconnected",s(),u&&(T=window.setTimeout(()=>K(u),5e3))}}catch{h="disconnected",s()}}function G(){T!==null&&(clearTimeout(T),T=null),u="";try{localStorage.removeItem(D)}catch{}k?.close(),k=null,h="disconnected",s()}async function z(e){if(!I)return;L=!1,v=!0;const t={role:"user",text:e};c=[...c,t],w(),s(),A();const a=P.find(l=>l.id===M)??P[0],i=[],n=c.filter(l=>!l.confirmId).slice(-14);for(let l=0;l<n.length;l++){const o=n[l];if(o.role!=="function"){if(o.role==="companion"){i.push({role:"user",parts:[{text:`[From desktop companion]: ${x(o.text,B)}`}]});continue}if(o.role==="model"&&o.functionCall){const d=[];o.text&&d.push({text:x(o.text,B)}),d.push({functionCall:{name:o.functionCall.name,args:o.functionCall.args}}),i.push({role:"model",parts:d});const y=n[l+1];y?.role==="function"?(i.push({role:"function",parts:[{functionResponse:{name:y.name??o.functionCall.name,response:{content:x(y.text,q)}}}]}),l++):i.push({role:"function",parts:[{functionResponse:{name:o.functionCall.name,response:{content:"(no result)"}}}]})}else{const d=o.role==="user"?"user":"model";i.push({role:d,parts:[{text:x(o.text??"",B)}]})}}}let r=!1,p=0,g=0;for(;!r&&!L&&p<20;){p++;let l;try{l=await ae(i,se(),a.id,a.maxOutputTokens)}catch(o){const d=String(o),y=d.match(/^RETRY_AFTER:(\d+)/);if(y){const S=Math.min(parseInt(y[1])||30,60);if(g++,g>=3){c=[...c,{role:"model",text:"⏳ Rate limited after retrying. Try again in a bit, Senpai~ 😅"}],w();break}p--;for(let b=S;b>0&&(O=`⏳ Rate limited — retrying in ${b}s`,s(),await new Promise(f=>setTimeout(f,1e3)),!L);b--);if(O="",s(),L)break;continue}c=[...c,{role:"model",text:"Ehh, something went wrong: "+d}],w();break}if(l.functionCall){const o=l.functionCall,d={};for(const[C,R]of Object.entries(o.args))d[C]=typeof R=="string"?R:JSON.stringify(R);const y=l.text??"",S={role:"model",text:y,functionCall:{name:o.name,args:d}};c=[...c,S],w(),s(),A();const b=[];y&&b.push({text:y}),b.push({functionCall:{name:o.name,args:d}}),i.push({role:"model",parts:b});let f="";try{f=await re(o.name,d)}catch(C){f="Tool error: "+C}o.name;const Q={role:"function",text:x(f,Z),name:o.name};c=[...c,Q],w(),i.push({role:"function",parts:[{functionResponse:{name:o.name,response:{content:x(f,q)}}}]}),s(),A(),await new Promise(C=>setTimeout(C,120))}else if(l.text!=null){const o=l.text??"",d={role:"model",text:o};c=[...c,d],w(),k?.readyState===WebSocket.OPEN&&k.send(JSON.stringify({type:"agent_message",role:"model",text:o}))}l.done&&(r=!0),s(),A()}p>=20&&(c=[...c,{role:"model",text:"I've been thinking too long on this... Let me know if you need anything else, Senpai~ 😅"}],w()),v=!1,O="",s(),A()}function m(e){return j(e??"")}function ce(e){const t=e.role==="user"?"user":e.role==="function"?"function":e.role==="companion"?"companion":"model",a=`nami-entry role-${t}`,i=e.role==="user"?"🧑 Senpai":e.role==="function"?`⚡ ${e.name??"tool"}`:e.role==="companion"?"🖥️ Desktop":"🐱 Nami";let n="";if(e.role==="function")n=`<div class="nami-tool-result">${m(e.text)}</div>`;else if(e.confirmId){const r=e.functionCall?.name??"",p=e.functionCall?.args??{},g=e.confirmStatus==="pending",l=r==="run_command"?`<pre class="nami-confirm-pre">$ ${m(p.command??"")}</pre>`:`<pre class="nami-confirm-pre">${m(JSON.stringify(p,null,2))}</pre>`,o=g?`<div class="nami-confirm-btns">
          <button class="nami-btn nami-btn-sm" data-confirm="approve" data-cid="${m(e.confirmId)}">✓ Approve</button>
          <button class="nami-btn nami-btn-sm nami-btn-danger" data-confirm="reject" data-cid="${m(e.confirmId)}">✗ Deny</button>
         </div>`:`<span style="font-size:0.82rem;font-weight:600;color:${e.confirmStatus==="approved"?"#b4e08e":"#ef6c78"}">${e.confirmStatus==="approved"?"✓ Approved":"✗ Denied"}</span>`;n=`<div class="nami-confirm">
      <div class="nami-confirm-title">🔒 Permission: ${m(r)}</div>
      ${l}
      ${o}
    </div>`,e.text&&(n=`<div class="nami-entry-text">${m(e.text)}</div>`+n)}else n=`<div class="nami-entry-text">${le(e.text??"")}</div>`;return`<div class="${a}">
    <div class="nami-entry-label ${t}">${i}</div>
    ${n}
  </div>`}function le(e){let t=m(e);return t=t.replace(/```(\w*)\n?([\s\S]*?)```/g,(a,i,n)=>`<div class="nami-code-block"${i?` data-lang="${j(i)}"`:""}><button class="nami-code-copy" data-copy="${j(n.trim())}">copy</button><pre><code>${n}</code></pre></div>`),t=t.replace(/`([^`\n]+)`/g,(a,i)=>`<code style="font-family:'IBM Plex Mono',monospace;font-size:0.82em;background:rgba(0,0,0,0.2);padding:0.1em 0.3em;border-radius:3px;">${i}</code>`),t=t.replace(/\*\*(.+?)\*\*/g,"<strong>$1</strong>"),t=t.replace(/\*(.+?)\*/g,"<em>$1</em>"),t=t.replace(/\n/g,"<br>"),t}function s(){const e=document.getElementById("nami-root");if(e){if(!I){e.innerHTML=de(),be();return}e.innerHTML=`
    <div class="nami-chat">
      ${pe()}
      ${me()}
      ${ue()}
      <div class="nami-log" id="nami-log">
        ${c.length===0?fe():c.map(ce).join("")}
        ${v?ge():""}
      </div>
      ${he()}
    </div>
    ${E?ye():""}
    ${$?ve():""}
  `,we()}}function de(){return`
    <div class="nami-setup">
      <div class="nami-setup-card">
        <div class="nami-setup-logo">🐱</div>
        <h1 class="nami-setup-title">Nami Agent 🌊</h1>
        <p class="nami-setup-subtitle">
          Ehh, so you want to use me on your phone too? Fine, fine~<br>
          I can open applets, launch apps, search the web, and keep you company. Just bring your own API key first.
        </p>
        <div class="nami-setup-instructions">
          <strong>How to get me running~ 🐱</strong>
          <ol>
            <li>Go to <a class="nami-setup-link" href="https://aistudio.google.com/apikey" target="_blank">aistudio.google.com/apikey</a></li>
            <li>Sign in with your Google account</li>
            <li>Click <strong>"Create API key"</strong></li>
            <li>Copy the key and paste it below</li>
          </ol>
          <br>
          Got multiple keys? Paste them separated by commas — I'll pick one at random. 😏
        </div>
        <div class="nami-setup-form" id="nami-setup-form">
          <input type="password" class="nami-input" id="nami-key-input" placeholder="Paste your Gemini API key(s)..." autocomplete="off" />
          <div class="nami-error" id="nami-setup-error"></div>
          <button type="button" class="nami-btn" id="nami-save-key-btn">Save Key</button>
        </div>
        <p class="nami-privacy-note">🔒 Your API key is stored only on this device. It's never sent anywhere except directly to Google's Gemini API.</p>
      </div>
    </div>
  `}function pe(){return`
    <div class="nami-topbar">
      <span class="nami-topbar-title">🐱 Nami</span>
      <div class="nami-topbar-model">
        <select id="nami-model-select">${P.map(t=>`<option value="${t.id}" ${t.id===M?"selected":""}>${t.label} · ${t.note}</option>`).join("")}</select>
      </div>
      <div class="nami-topbar-actions">
        <button class="nami-btn nami-btn-sm nami-btn-ghost" id="nami-companion-btn" title="Companion Mode">🖥️</button>
        <button class="nami-btn nami-btn-sm nami-btn-ghost" id="nami-settings-btn" title="Settings">⚙️</button>
      </div>
    </div>
  `}function me(){if(h==="disconnected"&&!u)return"";const e=h,t=h==="connected"?`Connected to <strong>${m(u)}</strong>`:h==="connecting"?`Connecting to ${m(u)}…`:`Disconnected from ${m(u)}`;return`
    <div class="nami-companion-bar ${h==="disconnected"?"disconnected":""}">
      <div class="nami-companion-dot ${e}"></div>
      <span class="nami-companion-label">${t}</span>
      ${h==="connected"?'<button class="nami-btn nami-btn-sm nami-btn-danger" id="nami-companion-disconnect">Disconnect</button>':""}
    </div>
  `}function ue(){return`<div class="nami-applet-strip">${F.map(t=>`<button class="nami-applet-chip" data-applet-id="${t.id}" data-applet-name="${m(t.name)}" data-applet-path="${m(t.path)}">${t.emoji} ${m(t.name)}</button>`).join("")}</div>`}function fe(){return`
    <div class="nami-welcome">
      <div class="nami-welcome-emoji">🐱</div>
      <div class="nami-welcome-text">
        Hey Senpai! Ask me anything or tap an applet to open it~<br>
        I can also connect to your desktop agent if you're away.
      </div>
    </div>
  `}function ge(){return`
    <div class="nami-entry role-model" style="padding:0.45rem 0.75rem;">
      <div class="nami-entry-label model">🐱 Nami</div>
      <div class="nami-thinking">
        ${O?`<span class="nami-rate-limit">${m(O)}</span>`:'<span class="nami-dot"></span><span class="nami-dot"></span><span class="nami-dot"></span><span class="nami-thinking-text">thinking...</span>'}
      </div>
    </div>
  `}function he(){return`
    <div class="nami-input-row">
      <textarea class="nami-chat-input" id="nami-chat-input"
        placeholder="${v?"Message to redirect Nami...":"Tell Nami what to do..."}"
        rows="1"
      ></textarea>
      <button class="nami-send-btn ${v?"stop":""}" id="nami-send-btn">
        ${v?"■":"Send"}
      </button>
    </div>
  `}function ye(){return`
    <div class="nami-modal-backdrop" id="nami-modal-backdrop">
      <div class="nami-modal">
        <h2 class="nami-modal-title">🖥️ Companion Mode</h2>
        <p class="nami-modal-subtitle">
          Connect to your desktop Nami Agent over your local Wi-Fi network.
          Messages will mirror between this phone and the desktop session.
        </p>
        <div class="nami-settings-group">
          <div class="nami-settings-label">Desktop IP address</div>
          <div class="nami-modal-row">
            <input type="text" class="nami-input" id="nami-companion-ip"
              value="${m(u)}"
              placeholder="e.g. 192.168.1.100"
              inputmode="url"
            />
          </div>
          <div style="font-size:0.75rem;color:var(--text-dim);margin-top:0.25rem;">
            Port ${W} is used automatically.
          </div>
        </div>
        <div style="display:flex;gap:0.5rem;flex-wrap:wrap;">
          <button class="nami-btn" id="nami-companion-connect-btn" style="flex:1;">
            ${h==="connected"?"Reconnect":"Connect"}
          </button>
          ${u?'<button class="nami-btn nami-btn-danger nami-btn-ghost" id="nami-companion-clear-btn">Clear</button>':""}
        </div>
        <button class="nami-btn nami-btn-ghost" id="nami-modal-close-btn">Close</button>
      </div>
    </div>
  `}function ve(){return`
    <div class="nami-modal-backdrop" id="nami-modal-backdrop">
      <div class="nami-modal">
        <h2 class="nami-modal-title">⚙️ Settings</h2>
        <div class="nami-settings-group">
          <div class="nami-settings-label">API Key</div>
          <input type="password" class="nami-input" id="nami-key-change-input" placeholder="Paste new key(s)..." autocomplete="off" />
          <button class="nami-btn nami-btn-sm" id="nami-key-change-btn">Update Key</button>
        </div>
        <div class="nami-settings-group">
          <div class="nami-settings-label">Session</div>
          <button class="nami-btn nami-btn-sm nami-btn-ghost" id="nami-clear-history-btn">🗑️ Clear chat history</button>
        </div>
        <button class="nami-btn nami-btn-ghost" id="nami-modal-close-btn">Close</button>
      </div>
    </div>
  `}function be(){document.getElementById("nami-save-key-btn")?.addEventListener("click",()=>{const e=document.getElementById("nami-key-input"),t=document.getElementById("nami-setup-error"),a=e?.value?.trim()??"";if(!a){t&&(t.textContent="Enter your Gemini API key first.");return}V(a),s()}),document.getElementById("nami-key-input")?.addEventListener("keydown",e=>{e.key==="Enter"&&document.getElementById("nami-save-key-btn")?.click()})}function A(){const e=document.getElementById("nami-log");e&&(e.scrollTop=e.scrollHeight)}function X(e){e.style.height="auto",e.style.height=Math.min(e.scrollHeight,140)+"px"}function we(){document.getElementById("nami-model-select")?.addEventListener("change",t=>{M=t.target.value;try{localStorage.setItem(J,M)}catch{}});const e=document.getElementById("nami-chat-input");e&&(e.addEventListener("input",()=>X(e)),e.addEventListener("keydown",t=>{t.key==="Enter"&&!t.shiftKey&&(t.preventDefault(),H())})),document.getElementById("nami-send-btn")?.addEventListener("click",()=>{if(v){L=!0,v=!1,s();return}H()}),document.querySelectorAll("[data-applet-id]").forEach(t=>{t.addEventListener("click",()=>{t.dataset.appletId;const a=t.dataset.appletName??"";t.dataset.appletPath,v||z(`Open ${a}`)})}),document.querySelectorAll("[data-copy]").forEach(t=>{t.addEventListener("click",()=>{const a=t.dataset.copy??"";navigator.clipboard.writeText(a).catch(()=>{}),t.textContent="copied!",setTimeout(()=>{t.textContent="copy"},1500)})}),document.querySelectorAll("[data-confirm]").forEach(t=>{t.addEventListener("click",()=>{t.dataset.confirm,t.dataset.cid})}),document.getElementById("nami-companion-btn")?.addEventListener("click",()=>{E=!0,$=!1,s()}),document.getElementById("nami-settings-btn")?.addEventListener("click",()=>{$=!0,E=!1,s()}),document.getElementById("nami-companion-disconnect")?.addEventListener("click",()=>{G()}),document.getElementById("nami-modal-backdrop")?.addEventListener("click",t=>{t.target.id==="nami-modal-backdrop"&&(E=!1,$=!1,s())}),document.getElementById("nami-companion-connect-btn")?.addEventListener("click",()=>{const t=document.getElementById("nami-companion-ip")?.value?.trim()??"";t&&(E=!1,K(t))}),document.getElementById("nami-companion-clear-btn")?.addEventListener("click",()=>{G(),E=!1,s()}),document.getElementById("nami-key-change-btn")?.addEventListener("click",()=>{const t=document.getElementById("nami-key-change-input")?.value?.trim()??"";t&&(V(t),$=!1,s())}),document.getElementById("nami-clear-history-btn")?.addEventListener("click",()=>{c=[],w(),$=!1,s()}),document.getElementById("nami-modal-close-btn")?.addEventListener("click",()=>{E=!1,$=!1,s()})}function H(){const e=document.getElementById("nami-chat-input"),t=e?.value?.trim()??"";!t||v||(e&&(e.value="",X(e)),z(t))}function _(){const e=document.getElementById("nami-root");if(!e)return;const t=window.visualViewport;t?(e.style.height=t.height+"px",e.style.top=t.offsetTop+"px"):e.style.height=window.innerHeight+"px"}function ke(){ne(),s(),window.visualViewport?(window.visualViewport.addEventListener("resize",_),window.visualViewport.addEventListener("scroll",_)):window.addEventListener("resize",_),_(),u&&K(u)}ke();
