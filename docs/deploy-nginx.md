# 部署：同源反代到 /chatry/

生产/演示部署不需要单独的域名和端口：复用本机已有的 nginx（443，证书已覆盖本机 IP），
把 `/chatry/` 反代到 chatry 的 8084 即可。前端构建基路径为 `/chatry/`，接口前缀同源，SSE 需关闭代理缓冲。

## nginx（/etc/nginx/conf.d/dsh.conf 内，location / 之前）

```nginx
    location = /chatry {
        return 301 /chatry/;
    }

    location ^~ /chatry/ {
        proxy_pass http://127.0.0.1:8084/;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Connection "";
        proxy_connect_timeout 10s;
        proxy_read_timeout 3600s;
        proxy_send_timeout 120s;
        proxy_buffering off;          # SSE 必需
        proxy_request_buffering off;
        proxy_redirect off;
    }
```

`proxy_pass` 末尾带 `/`：`/chatry/api/...` → 后端 `/api/...`，`/chatry/assets/...` → `/assets/...`。

## 应用侧配置

- `web/vite.config.js`：`base: '/chatry/'`
- 前端接口路径统一走 `BASE_PATH`（`web/src/config.js`），路由 `BrowserRouter basename='/chatry'`
- 应用 CORS 默认放行 `https://118.31.111.223`（`CORS_ALLOWED_ORIGINS` 可覆盖）
- 应用监听 8084，管理端口 9092 只绑 127.0.0.1

## 验证

```bash
curl -sk -o /dev/null -w '%{http_code}\n' https://118.31.111.223/chatry/
curl -sk -X POST https://118.31.111.223/chatry/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123456"}'
```

浏览器打开 <https://118.31.111.223/chatry/>（证书为本机自签，与 DSH GUI 相同，接受即可）。
