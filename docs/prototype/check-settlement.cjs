const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path');
const examples=[
 [[800,700,-700,-500,-300],3,1],
 [[-6,-89,-118,-53,-80,-31,-12,-24,-113,-42,91,78,80,47,83,32,60,6,58,33],16,2],
 [[-321,-530,-183,22,85,73,38,85,33,98,30,95,38,51,99,85,95,18,48,41],19,7],
 [[-6,1,1,1,1,1,1],6,6],
 [[0,0],0,0],
];
let source;
for(const file of ['tennis-flow.html','finance-proposal.html']) {
 const html=fs.readFileSync(path.join(__dirname,file),'utf8'),body=html.split('// Direct settlement portfolio,')[1].split('// End direct settlement portfolio.')[0];
 const code=body.slice(body.indexOf('function directSettlements'));
 if(source)assert.equal(source,code);source=code;
 const context=vm.createContext({});vm.runInContext(code,context);
 for(const [amounts,edges,maximum] of examples){
  const b=Object.fromEntries(amounts.map((n,i)=>[String(i).padStart(2,'0'),n])),p=context.directSettlements(b),rest=[...amounts],counts=Array(amounts.length).fill(0);
  assert.equal(p.length,edges);
  for(const x of p){assert(amounts[x.from]<0&&amounts[x.to]>0&&x.amount>0);rest[x.from]+=x.amount;rest[x.to]-=x.amount;counts[x.from]++}
  assert(rest.every(x=>x===0));assert.equal(Math.max(0,...counts),maximum);
 }
}
console.log('PASS: both prototypes match the approved settlement examples');
