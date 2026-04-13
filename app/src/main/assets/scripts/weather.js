// weather.js — 天气查询脚本，通过 http bridge 调用 wttr.in
// 调用方注入全局变量 LOCATION

var url = "https://wttr.in/" + LOCATION + "?format=3";

try {
    var resp = http.get(url);
    if (resp.status === 200) {
        JSON.stringify({success: true, data: resp.body});
    } else {
        JSON.stringify({success: false, error: "HTTP error: " + resp.status});
    }
} catch (e) {
    JSON.stringify({success: false, error: e.message || String(e)});
}
