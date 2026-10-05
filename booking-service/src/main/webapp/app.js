'use strict';
const $ = id => document.getElementById(id);
const ticketUrl = '/client-api/tickets';
const labels = {id:'ID',name:'Название',coordinateX:'Координата X',coordinateY:'Координата Y',creationDate:'Дата создания',price:'Цена',comment:'Комментарий',type:'Тип билета',venueId:'ID места',venueName:'Название места',venueCapacity:'Вместимость',venueType:'Тип места',personId:'ID человека'};
const ticketTypes = {VIP:'VIP',USUAL:'Обычный',BUDGETARY:'Бюджетный',CHEAP:'Дешёвый'};
const venueTypes = {BAR:'Бар',CINEMA:'Кинотеатр',MALL:'Торговый центр'};
const numeric = ['id','coordinateX','coordinateY','price','venueId','venueCapacity','personId'];
let editingId = null;
function el(tag,text) { const node=document.createElement(tag); if(text!==undefined) node.textContent=text; return node; }
function select(options) { const node=el('select'); for(const [value,label] of Object.entries(options)) { const o=el('option',label);o.value=value;node.append(o); } return node; }
function message(text,error=false) { $('message').textContent=text;$('message').className=error?'error':'';$('message').hidden=false; }
async function api(path,method='GET',body) {
  const options={method,headers:{Accept:'application/json'}};
  if(body!==undefined){options.headers['Content-Type']='application/json';options.body=JSON.stringify(body);}
  let response;
  try { response=await fetch(path,options); } catch { throw new Error('Нет соединения с сервером. Проверьте доступ к HTTPS-адресу.'); }
  if(response.status===204)return null;
  let data;try{data=await response.json();}catch{throw new Error(`Сервис вернул нечитаемый ответ (HTTP ${response.status}).`);}
  if(!response.ok){
    const violations=(data.violations||[]).map(v=>`${labels[v.field]||v.field}: ${v.message}`).join('\n');
    throw new Error(`Ошибка ${response.status}: ${data.message||data.error||'Не удалось выполнить запрос'}${violations?'\n'+violations:''}`);
  }return data;
}
function action(task){return async event=>{event?.preventDefault();try{await task();}catch(e){message(e.message,true);}};}
function appendField(parent,label,node){const wrapper=el('label',label);wrapper.append(node);parent.append(wrapper);}
function filterRow(){
  const row=el('div');row.className='condition';
  const field=select(labels),operator=select({equal:'Равно'}),value=el('input');value.placeholder='Значение';
  function configure(){
    const choices={equal:'Равно'};
    if(numeric.includes(field.value)||field.value==='creationDate'){choices.From='От (включительно)';choices.To='До (включительно)';}
    if(['name','comment','venueName'].includes(field.value))choices.Contains='Содержит (без регистра)';
    operator.replaceChildren(...select(choices).children);
    value.placeholder=field.value==='creationDate'?'05.10.2026 14:30:00':field.value==='type'?'VIP / USUAL / BUDGETARY / CHEAP':field.value==='venueType'?'BAR / CINEMA / MALL':'Значение';
  }
  field.addEventListener('change',configure);configure();
  appendField(row,'Поле',field);appendField(row,'Условие',operator);appendField(row,'Значение',value);
  const remove=el('button','Убрать');remove.type='button';remove.onclick=()=>row.remove();row.append(remove);
  row.parameter=()=>[field.value+(operator.value==='equal'?'':operator.value),value.value];$('filters').append(row);
}
function sortRow(){
  const row=el('div');row.className='condition';const field=select(labels),direction=select({asc:'По возрастанию',desc:'По убыванию'});
  appendField(row,'Поле сортировки',field);appendField(row,'Порядок',direction);
  const remove=el('button','Убрать');remove.type='button';remove.onclick=()=>row.remove();row.append(remove);
  row.parameter=()=>`${direction.value==='desc'?'-':''}${field.value}`;$('sorts').append(row);
}
function query(){
  const params=new URLSearchParams({page:$('page').value,size:$('size').value});
  for(const row of $('filters').children){const [key,value]=row.parameter();params.append(key,value);}
  if($('sorts').children.length)params.set('sort',[...$('sorts').children].map(row=>row.parameter()).join(','));
  return params.toString();
}
function money(value){return new Intl.NumberFormat('ru-RU',{maximumFractionDigits:8}).format(value);}
function display(value){return value==null?'Не указан':String(value);}
async function load(){
  const data=await api(`${ticketUrl}?${query()}`);$('tickets').replaceChildren();
  for(const t of data.items){
    const row=el('tr');
    const values=[t.id,`${t.name}\n${ticketTypes[t.type]}`,`X: ${t.coordinates.x}\nY: ${t.coordinates.y}`,t.creationDate,money(t.price),t.comment??'Без комментария',`${t.venue.name}\nID: ${t.venue.id} · ${venueTypes[t.venue.type]??'Тип не указан'}\nВместимость: ${t.venue.capacity}`,t.personId==null?'Свободен':`Человек №${t.personId}`];
    for(const value of values){const cell=el('td');String(value).split('\n').forEach((line,i)=>cell.append(el(i?'small':'span',line)));row.append(cell);}
    const cell=el('td'),button=el('button','Открыть');button.onclick=action(async()=>{await lookup(t.id);$('editor-title').scrollIntoView({behavior:'smooth'});});cell.append(button);row.append(cell);$('tickets').append(row);
  }
  if(!data.items.length){const row=el('tr'),cell=el('td','Билетов по заданным условиям нет.');cell.colSpan=9;row.append(cell);$('tickets').append(row);}
  $('page-info').textContent=`Страница ${data.page}; всего страниц: ${data.totalPages}; найдено билетов: ${data.totalElements}.`;
  $('previous').disabled=data.page<=1;$('next').disabled=data.page>=data.totalPages;
}
function resetEditor(){
  editingId=null;$('editor').reset();$('lookup-id').value='';$('editing').textContent='Создание нового билета. ID и дата назначаются автоматически.';
  $('save-ticket').textContent='Создать билет';$('delete-ticket').hidden=true;
}
async function lookup(id){
  const t=await api(`${ticketUrl}/${encodeURIComponent(id)}`),form=$('editor').elements;
  editingId=t.id;$('lookup-id').value=t.id;
  const values={name:t.name,price:t.price,type:t.type,x:t.coordinates.x,y:t.coordinates.y,comment:t.comment??'',venueName:t.venue.name,capacity:t.venue.capacity,venueType:t.venue.type??'',personId:t.personId??''};
  for(const [key,value]of Object.entries(values))form.namedItem(key).value=value;
  form.namedItem('commentNull').checked=t.comment===null;
  $('editing').textContent=`Изменение билета №${t.id}. Создан: ${t.creationDate}. ID места: ${t.venue.id}.`;
  $('save-ticket').textContent='Сохранить изменения';$('delete-ticket').hidden=false;showTicket(t,'Полученный билет');
}
function inputTicket(){
  const f=$('editor').elements,v=key=>f.namedItem(key).value,n=key=>v(key)===''?null:Number(v(key));
  return {name:v('name'),coordinates:{x:n('x'),y:n('y')},price:n('price'),type:v('type'),comment:f.namedItem('commentNull').checked?null:v('comment'),venue:{name:v('venueName'),capacity:n('capacity'),type:v('venueType')||null},personId:n('personId')};
}
function showTicket(t,title){
  $('result').replaceChildren(el('h3',title));const list=el('dl');
  const details={'ID билета':t.id,'Название':t.name,'Координаты':`X = ${t.coordinates.x}; Y = ${t.coordinates.y}`,'Создан':t.creationDate,'Цена':money(t.price),'Категория':ticketTypes[t.type],'Комментарий':t.comment??'Без комментария','Место':`${t.venue.name} (ID ${t.venue.id})`,'Вместимость':t.venue.capacity,'Тип места':venueTypes[t.venue.type]??'Не указан','Бронирование':t.personId==null?'Свободен':`Человек №${t.personId}`};
  for(const [key,value]of Object.entries(details))list.append(el('dt',key),el('dd',value));$('result').append(list);
}
$('add-filter').onclick=filterRow;$('add-sort').onclick=sortRow;
$('selection').onsubmit=action(load);
$('reset-selection').onclick=action(async()=>{$('filters').replaceChildren();$('sorts').replaceChildren();$('page').value=1;$('size').value=20;await load();});
$('previous').onclick=action(async()=>{$('page').value=Number($('page').value)-1;await load();});
$('next').onclick=action(async()=>{$('page').value=Number($('page').value)+1;await load();});
$('lookup').onsubmit=action(()=>lookup($('lookup-id').value));$('new-ticket').onclick=resetEditor;
$('editor').onsubmit=action(async()=>{const t=await api(editingId===null?ticketUrl:`${ticketUrl}/${editingId}`,editingId===null?'POST':'PUT',inputTicket());message(`Билет №${t.id} сохранён.`);showTicket(t,'Сохранённый билет');resetEditor();await load();});
$('delete-ticket').onclick=action(async()=>{if(!confirm(`Удалить билет №${editingId}?`))return;await api(`${ticketUrl}/${editingId}`,'DELETE');message('Билет удалён.');resetEditor();await load();});
$('average').onclick=action(async()=>{const r=await api(`${ticketUrl}/price/average`);$('result').replaceChildren(el('h3','Средняя цена'),el('p',`${money(r.average)}; билетов в расчёте: ${r.count}.`));});
$('min-comment').onclick=action(async()=>showTicket(await api(`${ticketUrl}/comment/min`),'Билет с минимальным комментарием'));
$('delete-price').onsubmit=action(async()=>{const price=$('delete-price').elements.namedItem('price').value;if(!confirm(`Удалить один свободный билет с ценой ${price}?`))return;showTicket(await api(`${ticketUrl}/price/${encodeURIComponent(price)}`,'DELETE'),'Удалённый билет');message('Один свободный билет удалён.');await load();});
$('vip').onsubmit=action(async()=>{const f=$('vip').elements;showTicket(await api(`/booking/sell/vip/${encodeURIComponent(f.namedItem('ticket').value)}/${encodeURIComponent(f.namedItem('person').value)}`,'POST'),'Проданная VIP-копия');message('VIP-копия создана и закреплена за человеком.');await load();});
$('cancel').onsubmit=action(async()=>{const person=$('cancel').elements.namedItem('person').value;const r=await api(`/booking/person/${encodeURIComponent(person)}/cancel`,'POST');$('result').replaceChildren(el('h3','Отмена бронирований'),el('p',`Человек №${r.personId}: отменено ${r.cancelledCount} бронирований. Билеты: ${r.cancelledTicketIds.join(', ')||'нет'}.`));message('Отмена бронирований выполнена.');await load();});
action(load)();
