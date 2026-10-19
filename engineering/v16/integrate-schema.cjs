// Synchronizes ONLY the two explicitly generator-owned table lists.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname,'../..');
const {tables} = JSON.parse(fs.readFileSync(path.join(__dirname,'schema-v5.json'),'utf8'));
const base='app/src/main/java/com/qq/closie/';
{
 const p=path.join(root,base+'life/data/database/LifeDatabase.kt');
 const body=fs.readFileSync(p,'utf8').replace(/\r\n/g,'\n');
 const marker='        FinanceChangeEntity::class,';
 const index=body.indexOf(marker);
 if(index<0) throw new Error('Missing schema integration marker');
 const start=index+marker.length, end=body.indexOf('\n    ]',start);
 if(end<0) throw new Error('Missing schema integration end');
 fs.writeFileSync(p,body.slice(0,start)+'\n'+tables.map(t=>'        '+t.cls+'::class,').join('\n')+'\n'+body.slice(end));
}
{
 const p=path.join(root,base+'data/backup/BackupImporter.kt');
 let body=fs.readFileSync(p,'utf8').replace(/\r\n/g,'\n');
 body=body.replace(/                if \(manifest\.formatVersion >= 5 && finance != null\) \{[\s\S]*?(?=                gson\.fromJson)/g,'');
 const block='                if (manifest.formatVersion >= 5 && finance != null) {\n                    require(finance.has("v16") && finance.get("v16").isJsonObject)\n                    val section = finance.getAsJsonObject("v16")\n'+
 tables.map(t=>'                    require(section.has("'+t.property+'") && section.get("'+t.property+'").isJsonArray)').join('\n')+'\n                }\n';
 const marker='                gson.fromJson(tree, LifeBackupPayload::class.java)';
 if(!body.includes(marker)) throw new Error('Missing backup integration marker');
 fs.writeFileSync(p,body.replace(marker,block+marker));
}
console.log('Synchronized table registration and required backup section keys.');
