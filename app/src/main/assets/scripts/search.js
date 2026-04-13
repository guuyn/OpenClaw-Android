// search.js — SearXNG 多引擎搜索脚本，通过 http bridge 发起请求
// 调用方注入全局变量 QUERY（搜索关键词）

var INSTANCES = [
    "https://searx.work",
    "https://searxng.no-logs.com",
    "https://search.bus-hit.me"
];

function search(query) {
    for (var i = 0; i < INSTANCES.length; i++) {
        var url = INSTANCES[i] + "/search?q=" +
                  encodeURIComponent(query) + "&format=json";
        try {
            var resp = http.get(url);
            if (resp.status === 200 && resp.body) {
                var data = JSON.parse(resp.body);
                var items = data.results || [];
                var results = [];
                for (var j = 0; j < Math.min(items.length, 5); j++) {
                    results.push({
                        title: items[j].title || "",
                        snippet: items[j].content || "",
                        url: items[j].url || ""
                    });
                }
                if (results.length > 0) {
                    return JSON.stringify({success: true, results: results});
                }
            }
        } catch (e) {
            // 当前实例失败，继续尝试下一个
        }
    }
    return JSON.stringify({success: false, error: "所有搜索实例均不可用"});
}

search(QUERY);
