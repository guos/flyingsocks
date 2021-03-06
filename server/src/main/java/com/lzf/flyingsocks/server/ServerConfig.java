package com.lzf.flyingsocks.server;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.lzf.flyingsocks.AbstractConfig;
import com.lzf.flyingsocks.Config;
import com.lzf.flyingsocks.ConfigInitializationException;
import com.lzf.flyingsocks.ConfigManager;
import com.lzf.flyingsocks.protocol.AuthMessage;
import com.lzf.flyingsocks.util.BaseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ServerConfig extends AbstractConfig implements Config {
    private static final Logger log = LoggerFactory.getLogger(ServerConfig.class);

    public static final String NAME = "config.server";

    private final List<Node> nodeList = new ArrayList<>();

    private String location;

    private String locationURL;

    ServerConfig(ConfigManager<?> configManager) {
        super(configManager, NAME);
    }

    @Override
    protected void initInternal() throws ConfigInitializationException {
        try(InputStream is = configManager.loadResource("classpath://config.properties")) {
            Properties p = new Properties();
            p.load(is);

            String location;
            if(configManager.isWindows()) {
                location = p.getProperty("config.location.windows");
            } else if(configManager.isMacOS()) {
                location = p.getProperty("config.location.mac");
            } else {
                location = p.getProperty("config.location.linux");
            }

            File folder = new File(location);
            if(!folder.exists())
                folder.mkdirs();

            if(!location.endsWith("/"))
                location += "/";

            this.location = location;

            if(configManager.isWindows() || !location.startsWith("/")) {
                this.locationURL = "file:///" + this.location;
            } else {
                this.locationURL = "file://" + this.location;
            }

            location += "config.json";
            File file = new File(location);

            if(!file.exists()) {
                makeTemplateConfigFile(file);
            }

            try(InputStream cis = file.toURI().toURL().openStream()) {
                byte[] b = new byte[102400];
                int len = cis.read(b);
                String json = new String(b, 0, len, StandardCharsets.UTF_8);
                JSONArray arr = JSON.parseArray(json);
                for(int i = 0; i < arr.size(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String name = obj.getString("name");
                    int port = obj.getIntValue("port");
                    int certPort = obj.getIntValue("cert-port");
                    int client = obj.getIntValue("max-client");

                    if(!BaseUtils.isPort(port)) {
                        log.error("Illegal Port {}, should be large than 0 and smaller than 65536", port);
                        System.exit(1);
                    }

                    EncryptType encryptType = EncryptType.valueOf(obj.getString("encrypt"));
                    AuthType authType = AuthType.valueOf(obj.getString("auth-type").toUpperCase());

                    if(encryptType == EncryptType.OpenSSL && !BaseUtils.isPort(certPort)) {
                        log.error("Illegal CertPort {}, should be large than 0 and smaller than 65536", certPort);
                        System.exit(1);
                    }

                    Node n = new Node(name, port, certPort, client, authType, encryptType);

                    switch (authType) {
                        case SIMPLE: {
                            String pwd = obj.getString("password");
                            n.putArgument("password", pwd);
                        } break;
                        case USER: {
                            String group = obj.getString("group");
                            n.putArgument("group", group);
                        }
                    }

                    nodeList.add(n);
                    log.info("Create node {}", n.toString());
                }
            }

        } catch (Exception e) {
            throw new ConfigInitializationException(e);
        }
    }


    private void makeTemplateConfigFile(File file) throws Exception {
        JSONArray arr = new JSONArray();
        JSONObject obj = new JSONObject();
        obj.put("name", "default");
        obj.put("port", 2020);
        obj.put("max-client", 10);
        obj.put("cert-port", 7060);
        obj.put("encrypt", "OpenSSL");
        obj.put("auth-type", "simple");
        obj.put("password", UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        arr.add(obj);

        FileWriter writer = new FileWriter(file);
        writer.write(arr.toJSONString());
        writer.close();
    }

    public String getLocation() {
        return location;
    }

    public String getLocationURL() {
        return locationURL;
    }

    public Node[] getServerNode() {
        return nodeList.toArray(new Node[0]);
    }

    /**
     * 服务器指定的认证类型
     */
    public enum AuthType {
        SIMPLE(AuthMessage.AuthMethod.SIMPLE), USER(AuthMessage.AuthMethod.USER);

        public final AuthMessage.AuthMethod authMethod;

        AuthType(AuthMessage.AuthMethod authMethod) {
            this.authMethod = authMethod;
        }
    }

    public enum EncryptType {
        None, OpenSSL, JKS
    }

    /**
     * 服务器配置节点类
     * 每个节点需要绑定不同的端口，并拥有各自的配置方案
     */
    public static class Node {
        public final String name;   //节点名称
        public final int port;      //绑定端口
        public final int certPort;  //收发CA证书端口
        public final int maxClient; //最大客户端连接数
        public final AuthType authType; //认证方式
        public final EncryptType encryptType;   //加密方式

        //认证参数
        private final Map<String, String> args = new HashMap<>(4);

        private Node(String name, int port, int certPort, int maxClient, AuthType authType, EncryptType encryptType) {
            this.name = Objects.requireNonNull(name);
            this.port = port;
            this.certPort = certPort;
            this.maxClient = maxClient;
            this.authType = Objects.requireNonNull(authType);
            this.encryptType = Objects.requireNonNull(encryptType);
        }

        private void putArgument(String key, String value) {
            args.put(key, value);
        }

        public String getArgument(String key) {
            synchronized (args) {
                return args.get(key);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append("name:").append(name).append(" port:")
                    .append(port).append(" cert-port:").append(certPort)
                    .append(" maxClient:").append(maxClient).append(" Auth:")
                    .append(authType.name());

            switch (authType) {
                case SIMPLE:
                    sb.append(" password:").append(args.get("password")); break;
                case USER:
                    sb.append(" group:").append(args.get("group")); break;
            }

            sb.append(" Encrypt:").append(encryptType.name());

            return sb.toString();
        }
    }
}
