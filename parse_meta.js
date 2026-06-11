const https = require('https');
const fs = require('fs');

https.get('https://meta.ai/share/c/sb0qcrsCXK?utm_source=meta_ai_web_share_copy_link&utm_medium=share&utm_campaign=ecto_share', (resp) => {
  let data = '';
  resp.on('data', (chunk) => { data += chunk; });
  resp.on('end', () => {
    const matches = [...data.matchAll(/self\.__next_f\.push\(\[1,\"(.*?)\"\]\)/g)];
    let fullText = "";
    for (const match of matches) {
      try {
        let text = match[1].replace(/\\\"/g, '"').replace(/\\\\/g, '\\').replace(/\\n/g, '\n');
        fullText += text;
      } catch (e) {}
    }
    
    // The chat text is usually in a JSON object in these chunks.
    // Let's just output the whole thing to a file so we can view it properly.
    fs.writeFileSync('C:\\Users\\gbust\\Smarty\\meta_ai_raw.txt', fullText);
    console.log("Wrote to meta_ai_raw.txt");
  });
}).on("error", (err) => {
  console.log("Error: " + err.message);
});
