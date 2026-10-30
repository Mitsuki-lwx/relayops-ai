import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

// Explicit local development bootstrap. No task is created and no key is printed.
const config = Object.fromEntries(readFileSync(new URL("../ops/.env", import.meta.url), "utf8").split(/\r?\n/).filter(l => l.includes("=") && !l.startsWith("#")).map(l => [l.slice(0,l.indexOf("=")),l.slice(l.indexOf("=")+1)]));
const base = process.env.RELAYOPS_BASE_URL ?? "http://127.0.0.1:18080";
const local = new URL(base);
if (local.hostname !== "127.0.0.1" || local.protocol !== "http:") throw Error("Bootstrap is restricted to the local development API");
const cookies = new Map(); let csrf;
async function api(path, init={}) {
  const headers = new Headers(init.headers);
  if(cookies.size) headers.set("Cookie", [...cookies].map(([k,v])=>`${k}=${v}`).join("; "));
  if(init.method && csrf) headers.set(csrf.headerName,csrf.token);
  const response = await fetch(base+path,{...init,headers,redirect:"manual",signal:AbortSignal.timeout(10000)});
  for(const value of response.headers.getSetCookie()){const pair=value.split(";")[0],at=pair.indexOf("=");cookies.set(pair.slice(0,at),pair.slice(at+1));}
  if(!response.ok) throw Error(`Local API rejected bootstrap (${response.status}); check OWNER access and refresh destination state`);
  return response.status===204?null:response.json();
}
try {
  csrf=await api("/auth/csrf");
  await api("/auth/login",{method:"POST",body:new URLSearchParams({username:process.env.RELAYOPS_BROWSER_USER??"owner.a",password:config.RELAYOPS_DEMO_PASSWORD})});
  csrf=await api("/auth/csrf");
  const me=await api("/api/v1/me");const keys={};
  for(const workspace of me.workspaces.filter(w=>w.role==="OWNER")){
    const prefix=`/api/v1/workspaces/${workspace.id}`;
    for(const target of await api(prefix+"/destinations")){
      if(!target.enabled || target.catalogId!=="demo-local-v1")continue;
      const key=await api(prefix+`/destinations/${target.id}/reveal-key`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({acknowledgeSecretRead:true})});
      keys[key.keyId]={secret:key.secret,workspaceId:workspace.id};
    }
  }
  if(!Object.keys(keys).length) throw Error("Create and enable a local demo destination in the workbench first");
  const folder=new URL("../artifacts/receiver/",import.meta.url);mkdirSync(folder,{recursive:true});
  writeFileSync(new URL("keys.json",folder),JSON.stringify(keys),{mode:0o600});
  console.log(`Registered ${Object.keys(keys).length} local destination key(s). Read access was audited; no model calls made.`);
} catch(error) {console.error(error instanceof Error?error.message:"Bootstrap failed");process.exitCode=1;}
