// Run with Node.js 18+: node tests/client_failure_test.mjs
// Exercise the actual client functions without a browser or third-party packages.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

class Element {
  children=[]; attributes={}; textContent=''; className=''; value='';
  classList={contains:name=>this.className.split(' ').includes(name)};
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.children=[...nodes];}
  querySelectorAll(){return [];}
  setAttribute(name,value){this.attributes[name]=value;}
  focus(){}
  scrollIntoView(){}
}
const nodes=new Map();
const node=id=>{if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id);};
node('page').value='1';node('size').value='20';
let fetchImpl;
let onTimeout;
const context=vm.createContext({
  document:{getElementById:node,createElement:()=>new Element()},
  URLSearchParams,AbortController,Intl,
  setTimeout:callback=>{onTimeout=callback;return 1;},
  clearTimeout:()=>{onTimeout=undefined;},
  fetch:(...args)=>fetchImpl(...args)
});
const source=await readFile(new URL('../booking-service/src/main/webapp/app.js',import.meta.url),'utf8');
vm.runInContext(source.slice(0,source.indexOf("$('add-filter').onclick=")),context);
const response=(status,data)=>({status,ok:status>=200&&status<300,json:async()=>data});

fetchImpl=async(path,options)=>{
  assert.equal(options.cache,'no-store');
  assert(options.signal instanceof AbortSignal);
  throw new TypeError('Failed to fetch');
};
node('result').append(new Element());
await context.action(async()=>context.api('/booking/person/7/cancel','POST'))({
  preventDefault(){},currentTarget:{closest:()=>node('cancel')}
});
assert.equal(node('result').children.length,0,'stale successful result must disappear');
const notice=node('cancel').children[0];
assert.equal(notice.className,'message error');
assert.match(notice.textContent,/Нет соединения/);
assert.match(notice.textContent,/Результат операции неизвестен/);

for(const status of [502,503,504]){
  fetchImpl=async()=>response(status,{status,message:'Ticket Service недоступен'});
  await assert.rejects(context.api('/booking/person/7/cancel','POST'),new RegExp('Ошибка '+status));
}
fetchImpl=async()=>response(204,null);
await assert.rejects(context.api('/booking/person/7/cancel','POST'),/пустой ответ/);
assert.equal(await context.api('/client-api/tickets/7','DELETE'),null);
fetchImpl=async()=>({status:200,ok:true,json:async()=>{throw new SyntaxError('HTML');}});
await assert.rejects(context.api('/client-api/tickets'),/нечитаемый ответ/);
fetchImpl=async()=>response(200,null);
await assert.rejects(context.api('/client-api/tickets'),/не вернул результат/);

fetchImpl=(_,options)=>new Promise((resolve,reject)=>{
  options.signal.addEventListener('abort',()=>reject(new Error('aborted')),{once:true});
});
const pending=context.api('/booking/person/7/cancel','POST');
onTimeout();
await assert.rejects(pending,/30 секунд/);
assert.equal(onTimeout,undefined,'the timer must be cleared after failure');

fetchImpl=async()=>response(502,{message:'Ticket Service недоступен'});
node('tickets').append(new Element());
await assert.rejects(context.load(),/502/);
assert.equal(node('tickets').children.length,0,'stale collection must disappear');
assert.equal(node('next').disabled,true);
assert.match(node('page-info').textContent,/Не удалось загрузить/);
context.message('Билет сохранён.',false,node('editor'));
await context.refreshAfterMutation();
assert.equal(node('editor').children[0].textContent,'Билет сохранён.');
assert.match(node('selection').children[0].textContent,/502/);

fetchImpl=async()=>response(200,{items:[],page:1,totalPages:0,totalElements:0});
await context.load();
assert.match(node('page-info').textContent,/найдено билетов: 0/);
assert.equal(onTimeout,undefined);
console.log('PASS: network failure, 502/503/504, timeout, invalid/empty replies, stale results, refresh failure and recovery.');
