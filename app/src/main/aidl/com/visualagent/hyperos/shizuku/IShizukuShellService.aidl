package com.visualagent.hyperos.shizuku;

interface IShizukuShellService {
    String execCommand(String command);
    int getUid();
    void destroy();
}
