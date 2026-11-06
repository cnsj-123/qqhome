// Import the unchanged local Figwright SVG exports. No network or package installation.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const source = process.argv[2];
if (!source) throw Error('Pass the local Figwright entry-icons export directory');
const nodes = {food:'12816',transport:'12872',shopping:'12881',home:'12887',coffee:'12893',garden:'12810',gift:'12852',medical:'12859',study:'12866',breakfast:'12967',lunch:'12972',dinner:'12977',tea:'12982',supper:'12987'};
Object.assign(nodes,{note:'12898',settings:'12956',review:'1929:20082',back:'1931:23584',search:'12952'});
const attrs = text => Object.fromEntries([...text.matchAll(/([\w-]+)="([^"]*)"/g)].map(m=>[m[1],m[2]]));
const esc = value => value.replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;');
for (const [key, id] of Object.entries(nodes)) {
  const nodeId=id.includes(':') ? id : '1925:'+id;
  const svg = fs.readFileSync(path.join(source, nodeId.replace(':','-')+'.svg'), 'utf8');
  if (/\btransform=/.test(svg)) throw Error('Unconverted SVG transform: '+key);
  const size = attrs(svg.match(/<svg\s+([^>]+)/)[1]);
  const box = size.viewBox.split(/\s+/).map(Number);
  if (box[0] || box[1] || !box[2] || !box[3]) throw Error('Unexpected viewport');
  const paths = [...svg.matchAll(/<path\s+([^>]+)\/?\s*>/g)].map(match=>{
    const a=attrs(match[1]);
    const color=value=> {
      if (!value || value==='none') return '@android:color/transparent';
      const resolved=({white:'#FFFFFF',black:'#000000',transparent:'#00000000'})[value] || value;
      if (!/^#[0-9a-f]{6}([0-9a-f]{2})?$/i.test(resolved)) throw Error('Unsupported Android color: '+value);
      return resolved;
    };
    const properties={pathData:a.d,fillColor:color(a.fill || size.fill),strokeColor:color(a.stroke),strokeWidth:a['stroke-width'] || '0',strokeLineCap:a['stroke-linecap'] || 'butt',strokeLineJoin:a['stroke-linejoin'] || 'miter'};
    return '    <path '+Object.entries(properties).map(([k,v])=>'android:'+k+'="'+esc(v)+'"').join(' ')+' />';
  });
  if (!paths.length) throw Error('Empty icon: '+key);
  fs.writeFileSync(path.join(root,`app/src/main/res/raw/finance_entry_${key}.svg`),svg);
  fs.writeFileSync(path.join(root,`app/src/main/res/drawable/lifeos_finance_entry_${key}.xml`),
    `<!-- Figma ${nodeId}; unchanged viewport and path geometry; monochrome tint supplied by caller. -->\n`+
    `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="${box[2]}dp" android:height="${box[3]}dp" android:viewportWidth="${box[2]}" android:viewportHeight="${box[3]}">\n${paths.join('\n')}\n</vector>\n`);
}
console.log('Imported '+Object.keys(nodes).length+' exact Figma entry icons');
