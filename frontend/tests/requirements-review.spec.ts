import {test,expect,type Page} from "@playwright/test";
import {readFileSync} from "node:fs";
import {analysisReport} from "../src/analysisReport";
import type {ResultBody} from "../src/resultTypes";
import {requireFakeModel} from "./require-fake";

const evidence=[{paragraphId:"p0001",quote:"上传需要登录。"}];
const body:ResultBody={data:{schemaVersion:"requirements.review.result.v1",title:"上传需求评审",summary:{text:"上传需要登录",evidence},keyPoints:[],constraints:[],unknowns:[],review:{findings:[{kind:"GAP",text:"文件上限待确认",question:"文件大小上限是多少？",evidence}],acceptanceCriteria:[{title:"拒绝未登录上传",given:"用户未登录",when:"提交上传",then:"拒绝上传",evidence,assumptions:["具体拒绝状态码待确认。"]}]}},paragraphs:[{paragraphId:"p0001",text:"上传需要登录。DO_NOT_EXPORT_FULL_SOURCE"}]};
const job={jobId:"33333333-3333-4333-8333-333333333333",title:"上传需求评审",status:"SUCCEEDED",applicationId:"app",modelProfile:"fake-review-v1",errorCode:null,createdAt:"2026-11-02T00:00:00Z",payloadExpiresAt:"2099-01-01T00:00:00Z",modelCalls:1,version:3,parentJobId:null,taskType:"REQUIREMENTS_REVIEW"};
async function fixture(page:Page,role="OWNER") {
  const state={reads:0,denied:false,delayProfile:false,waiting:false,release:()=>{},drop:false,posts:[] as {key:string|undefined,body:Record<string,unknown>}[]};
  await page.route(/\/(?:api\/v1|auth)\//,async route=>{
    const url=new URL(route.request().url()),path=url.pathname;let value:unknown,status=200;
    if(path==="/auth/csrf")value={token:"fixture",headerName:"X-CSRF-TOKEN"};
    else if(path==="/api/v1/me")value={username:"fixture.owner",workspaces:[{id:"space",name:"评审空间",role,tenantId:"tenant",tenantName:"验收公司"}]};
    else if(path==="/api/v1/organizations")value=[];
    else if(path.endsWith("/applications"))value=[{id:"app",name:"验收应用"}];
    else if(path.endsWith("/model-profile")){
      if(url.searchParams.has("taskType")&&state.delayProfile){await new Promise<void>(resolve=>{state.release=resolve;state.waiting=true;});status=403;}
      value={profileId:url.searchParams.has("taskType")?"fake-review-v1":"fake-extract-v1",promptVersion:"fixture",schemaVersion:"fixture",billable:false,creditsPerJob:1,provider:"fake",modelName:"假模型"};
    }
    else if(path.endsWith("/budget"))value={credits:{dailyLimit:20,reserved:0,spent:1,uncertain:0}};
    else if(path.endsWith("/destinations"))value=[];
    else if(path.endsWith("/jobs")&&route.request().method()==="POST"){
      state.posts.push({key:route.request().headers()["idempotency-key"],body:route.request().postDataJSON()});
      if(state.drop){await route.abort();return;}status=202;value={jobId:job.jobId,status:"QUEUED"};
    }
    else if(path.endsWith("/jobs"))value=[job];
    else if(path.endsWith("/deliveries"))value=[];
    else if(path.endsWith("/result")){state.reads++;status=state.denied?403:200;value=state.denied?{code:"FORBIDDEN",message:"评审权限已撤销"}:body;}
    else if(path.endsWith(job.jobId))value=job;
    else throw new Error("Unexpected fixture path "+path);
    await route.fulfill({status,json:value}).catch(()=>{});
  });
  await page.goto("/");await expect(page.locator(".job-row")).toHaveCount(1);return state;
}

for(const width of [1440,768,390])test(`review findings and real download at ${width}`,async({page})=>{
  await page.setViewportSize({width,height:1000});const state=await fixture(page);
  await page.locator(".job-row").click();await page.getByRole("button",{name:"查看结果与原文依据",exact:true}).click();
  await expect(page.getByRole("heading",{name:/验收草稿/})).toBeVisible();await expect(page.locator('[data-result-section="reviewFindings"]')).toContainText("不证明某项要求缺失");
  await expect(page.locator('[data-result-section="acceptanceCriteria"]')).toContainText("具体拒绝状态码待确认");
  const event=page.waitForEvent("download");await page.getByRole("button",{name:"下载分析报告",exact:true}).click();const file=await event;
  await file.saveAs(`../artifacts/review-fixture-${width}.md`);const text=readFileSync(`../artifacts/review-fixture-${width}.md`,"utf8");
  expect(text).toContain("Given");expect(text).toContain("待人工确认");expect(text).not.toContain("DO_NOT_EXPORT_FULL_SOURCE");expect(state.reads).toBe(2);expect(state.posts).toHaveLength(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:`../artifacts/review-fixture-${width}.png`,fullPage:true});
});

test("purpose profile ignores an old denial and pending review survives reload",async({page})=>{
  const state=await fixture(page);await page.getByRole("button",{name:"新建文档",exact:true}).click();
  state.delayProfile=true;await page.getByLabel("任务用途").selectOption("REQUIREMENTS_REVIEW");await expect.poll(()=>state.waiting).toBe(true);
  await page.getByLabel("任务用途").selectOption("EXTRACT");state.release();await expect(page.getByRole("alert")).toHaveCount(0);
  state.delayProfile=false;await page.getByLabel("任务用途").selectOption("REQUIREMENTS_REVIEW");await page.getByLabel("技术文档").fill("上传需要登录。");
  state.drop=true;await page.getByRole("button",{name:/^提交任务/}).click();await expect(page.getByRole("button",{name:/使用原幂等键重试/})).toBeVisible();
  expect(state.posts[0]!.body.taskType).toBe("REQUIREMENTS_REVIEW");await page.reload();
  await expect(page.getByLabel("任务用途")).toHaveValue("REQUIREMENTS_REVIEW");await expect(page.getByLabel("任务用途")).toBeDisabled();
  state.drop=false;await page.getByRole("button",{name:/使用原幂等键重试/}).click();await expect.poll(()=>state.posts.length).toBe(2);
  expect(state.posts[1]).toEqual(state.posts[0]);
});

test("review export reauthorizes",async({page})=>{
  const state=await fixture(page);await page.locator(".job-row").click();await page.getByRole("button",{name:"查看结果与原文依据",exact:true}).click();await expect(page.locator(".result")).toBeVisible();
  state.denied=true;let downloads=0;page.on("download",()=>downloads++);await page.getByRole("button",{name:"下载分析报告",exact:true}).click();
  await expect(page.getByRole("alert")).toContainText("评审权限已撤销");await expect(page.locator(".result")).toHaveCount(0);expect(downloads).toBe(0);
});

test("review viewer has no result or export control",async({page})=>{
  const state=await fixture(page,"VIEWER");await page.locator(".job-row").click();
  await expect(page.getByRole("button",{name:"查看结果与原文依据",exact:true})).toHaveCount(0);
  await expect(page.getByRole("button",{name:"下载分析报告",exact:true})).toHaveCount(0);expect(state.reads).toBe(0);
});

test("review Markdown keeps all generated fields literal",()=>{
  const result=structuredClone(body),payload="```\n<img src=x onerror=alert(1)>\n````";
  result.data.review!.findings[0]!.question=payload;result.data.review!.acceptanceCriteria[0]!.then=payload;result.data.review!.acceptanceCriteria[0]!.assumptions=[payload];
  const report=analysisReport(job,result,new Date(0));expect(report).toContain(payload);expect(report).toContain("`````text");expect(report).not.toContain("DO_NOT_EXPORT_FULL_SOURCE");
  let fence=0;for(const line of report.split("\n")){const start=/^(`+)text$/.exec(line);if(!fence&&start){fence=start[1]!.length;continue;}if(fence&&line==="`".repeat(fence)){fence=0;continue;}if(!fence)expect(line).not.toContain("<img");}expect(fence).toBe(0);
});

test("provisioned owner completes a persisted review and exports its draft",async({page})=>{
  test.skip(!process.env.RELAYOPS_E2E_CONFIG,"Run dedicated browser script");const cfg=Object.fromEntries(readFileSync(process.env.RELAYOPS_E2E_CONFIG!,"utf8").split(/\r?\n/).filter(l=>l.includes("=")).map(l=>[l.slice(0,l.indexOf("=")),l.slice(l.indexOf("=")+1)]));
  await page.goto("/");await page.getByLabel("账号",{exact:true}).fill("release.owner");await page.getByLabel("密码",{exact:true}).fill(cfg.RELAYOPS_DEMO_PASSWORD);await page.getByRole("button",{name:"登录工作台",exact:true}).click();
  await expect(page.getByRole("heading",{name:"AI 任务工作台",exact:true})).toBeVisible();
  await requireFakeModel(page);
  await page.getByRole("button",{name:"新建文档",exact:true}).click();await page.getByLabel("任务用途").selectOption("REQUIREMENTS_REVIEW");await page.getByLabel("技术文档").fill("上传必须登录。\n\n缺少文件大小限制。");
  await page.getByRole("button",{name:/^提交任务/}).click();const button=page.getByRole("button",{name:"下载分析报告",exact:true});await expect(button).toBeVisible({timeout:25000});
  const event=page.waitForEvent("download");await button.click();const file=await event;await file.saveAs("../artifacts/review-persisted.md");expect(readFileSync("../artifacts/review-persisted.md","utf8")).toContain("验收草稿");await expect(page.locator(".detail-meta")).toContainText("调用边界记录：1 次");
});
