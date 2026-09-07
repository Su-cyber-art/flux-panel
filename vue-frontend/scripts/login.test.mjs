import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import fs from 'node:fs'
import assert from 'node:assert/strict'
import { test, afterEach } from 'node:test'
const originalWindow=globalThis.window, originalDocument=globalThis.document, originalFetch=globalThis.fetch
afterEach(()=>{globalThis.window=originalWindow;globalThis.document=originalDocument;globalThis.fetch=originalFetch})
const require = createRequire(new URL('../package.json', import.meta.url))
const ts=require('typescript'), vue=require('vue'), {parse,compileScript}=require('@vue/compiler-sfc')
const root=fileURLToPath(new URL('../', import.meta.url))
const transpile=s=>ts.transpileModule(s,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ES2022}}).outputText
const {manageCaptcha}=await import('data:text/javascript;base64,'+Buffer.from(transpile(fs.readFileSync(root+'src/utils/captcha.ts','utf8'))).toString('base64'))
let code=compileScript(parse(fs.readFileSync(root+'src/pages/Login.vue','utf8')).descriptor,{id:'login-flow'}).content
code=transpile(code.replace(/^import .+$/gm,'').replace('export default','const component =').replaceAll('import.meta.env.DEV','false').replaceAll('import.meta.env.VITE_UI_PREVIEW',"'false'"))+'\nreturn component;'
const wait=()=>new Promise(r=>setImmediate(r))
for(const loginCode of [0,-1]) test(loginCode===0 ? 'captcha success submits its proof once and enters dashboard' : 'login rejection remains visible and a retry requests a new captcha', async()=>{
 let sdk, finish, checks=0;const requests=[],routes=[],sessions=[],errors=[]
 globalThis.window={TAC:class{constructor(config){this.config=config;sdk=this}createCaptcha(){return this.config.requestCaptchaData()}init(){this.reloadCaptcha()}showLoading(){}destroyWindow(){window.currentCaptcha=undefined}}}
 globalThis.document={documentElement:{classList:{contains:()=>false}}}
 globalThis.fetch=async url=>new Response(JSON.stringify(url.endsWith('/generate')?{code:200,data:{id:'fixture',type:'SLIDER',backgroundImage:'fixture'}}:{code:200,data:{validToken:'proof'}}))
 const deps={...vue,_defineComponent:x=>x,onUnmounted:()=>{},useRouter:()=>({push:path=>{routes.push(path);return Promise.resolve()}}),NInput:{},NButton:{},Logo:{},useToast:()=>({error:e=>errors.push(e),success:()=>{}}),useAuthStore:()=>({setSession:(...args)=>sessions.push(args)}),useConfigStore:()=>({version:'test'}),checkCaptcha:async()=>{checks++;return {code:0,data:1}},login:async data=>{requests.push({...data});return loginCode===0?{code:0,data:{token:'jwt-fixture',role_id:0,name:'demo'}}:{code:-1,msg:'用户名或密码错误'}},getBaseURL:()=>'/api/v1/',manageCaptcha,isWebViewFunc:()=>false,bgImage:''}
 const keys=Object.keys(deps), app=new Function(...keys,code)(...Object.values(deps)).setup({}, {expose(){}})
 app.form.username='demo'; app.form.password='fixture-password';app.captchaContainer.value={}
 await app.handleLogin();await wait()
 await sdk.config.validCaptcha('fixture',{startTime:new Date(1710000000000),stopTime:new Date(1710000001250),trackList:[]},{showTips:(_,__,cb)=>{finish=cb}})
 finish();await wait()
 assert.equal(requests.length,1);assert.equal(requests[0].captchaId,'proof');assert.equal(app.loading.value,false)
 if(loginCode===0){assert.deepEqual(routes,['/dashboard']);assert.equal(sessions.length,1)}else{assert.equal(routes.length,0);assert.deepEqual(errors,['用户名或密码错误']);assert.equal(app.submitError.value,'用户名或密码错误');await wait();assert.equal(app.submitError.value,'用户名或密码错误');app.onPassword('corrected-fixture-password');assert.equal(app.submitError.value,'');await app.handleLogin();await wait();assert.equal(checks,2);assert.equal(app.showCaptcha.value,true);app.closeCaptcha()}
})
