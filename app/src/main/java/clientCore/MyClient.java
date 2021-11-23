package clientCore;

import android.annotation.SuppressLint;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import clientCore.exceptions.ClientException;
import clientCore.exceptions.NoServerFoundException;

public class MyClient {
    // 属性
    // 控制连接
    Socket controlSocket = null;
    BufferedReader controlSocketReader = null;
    BufferedWriter controlSocketWriter = null;

    // 被动数据连接
    Socket dataSocket = null;

    // 主动数据连接
    ServerSocket serverSocket = null;

    // 用户名和密码
    String username;
    String password;

    // PASV记录下来的服务器主机名和端口号
    String serverHost;
    int serverPort;

    // 一些枚举
    enum ClientPattern {PASSIVE, ACTIVE}
    volatile ClientPattern clientPattern = ClientPattern.PASSIVE;
    enum TypeOfCode {BINARY, ASCII}
    volatile TypeOfCode typeOfCode = TypeOfCode.BINARY;

    /**
     * 建立连接
     * @param host 主机
     * @param port 端口号
     * @throws NoServerFoundException 失败就是没和服务器建立连接
     */
    MyClient(String host, int port) throws NoServerFoundException {
        // 建立控制连接
        try {
            controlSocket = new Socket(host, port);
            controlSocketReader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
            controlSocketWriter = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream()));
        } catch (IOException e) {
            throw new NoServerFoundException(String.format("无法与服务器(host:%s,port:%d)建立连接", host, port));
        }
    }

    /**
     * 用户登录系统
     * @param username 用户名
     * @param password 密码
     * @return 是否登陆成功
     */
    public synchronized boolean logIn(String username, String password) {
        this.username = username;
        this.password = password;
        readAll();

        try {
            // 输入用户名
            write(String.format("USER %s", username));
            String resp = controlSocketReader.readLine();
            if (resp.startsWith("230")) {
                // 不需要密码的用户名
                return true;
                // 成功连接
            } else if (resp.startsWith("331")) {
                // 需要密码
                write(String.format("PASS %s", password));
                resp = controlSocketReader.readLine();
                if (resp != null && resp.startsWith("230")) {
                    return true;
                }
            }
        } catch (IOException e) {
            readAll();
            return false;
        }
        return false;
    }

    /**
     * 看是否有数据流在传输，有的话关闭数据流，没有的话直接关掉所有的控制socket
     * @throws ClientException
     */
    public synchronized void quit() throws ClientException {
        readAll();

        try {
            write("QUIT");
            String resp = controlSocketReader.readLine();
            if (resp != null && resp.startsWith("221")) {
                if (controlSocket != null) {
                    controlSocket.close();
                }
                if (dataSocket != null) {
                    dataSocket.close();
                }
                if (controlSocketReader != null) {
                    controlSocketReader.close();
                }
                if (controlSocketWriter != null) {
                    controlSocketWriter.close();
                }
            } else {
                readAll();
                throw new ClientException(String.format("username: %s can't quit", username));
            }
        } catch (IOException e) {
            readAll();
            Log.e("quit: IOException", e.getMessage());
            throw new ClientException(String.format("username: %s can't quit", username));
        }
    }

    /**
     * 设成被动模式，获取server发过来的port和host，存下来备用
     * @throws ClientException
     */
    public synchronized void pasv() throws ClientException {
        readAll();
        String[] hostPort = null;
        try {
            write("PASV");
            String resp = controlSocketReader.readLine();
            if (resp == null) {
                readAll();
                throw new ClientException("Illegal response to command 'PASV'");
            }
            Pattern p = Pattern.compile("(?<=227 Entering Passive Mode \\()([0-9]+,){5}([0-9]+)(?!\\))");
            Matcher m = p.matcher(resp);
            if (m.find()) {
                hostPort = m.group().split(",");
            } else {
                readAll();
                throw new ClientException("Illegal response to command 'PASV'");
            }
            serverHost = hostPort[0] + "." + hostPort[1] + "." + hostPort[2] + "." + hostPort[3];
            serverPort = Integer.parseInt(hostPort[4]) * 256 + Integer.parseInt(hostPort[5]);
            clientPattern = ClientPattern.PASSIVE;
        } catch (IOException e) {
            readAll();
            Log.e("pasv:IOException", e.getMessage());
            throw new ClientException(e.getMessage());
        }
    }

    /**
     * 主动模式，client打开数据socket等待服务器链接
     * @throws ClientException 端口冲突；
     *                         关于socket的IO异常；
     *                         关于getLocalAddress的UnknownHostException
     */
    public synchronized void port() throws ClientException {
        readAll();
        boolean success = false;
        int p1 = 1, p2 = 1;
        ServerSocket newServerSocket = null;
        while (!success) {
            if (p2 < 256) {
                p2++;
            } else if (p1 < 256) {
                p1++;
            } else {
                readAll();
                throw new ClientException("no available port");
            }
            int port = p1 * 256 + p2;
            try {
                newServerSocket = new ServerSocket(port);
            } catch (IOException e) {
                continue;
            }
            success = true;
        }
        if (this.serverSocket != null) {
            try {
                this.serverSocket.close();
            } catch (IOException e) {
                readAll();
                Log.e("port:IOException", e.getMessage());
                throw new ClientException(e.getMessage());
            }
        }
        this.serverSocket = newServerSocket;
        String localAddress;
        try {
            localAddress = getLocalIPAddr();
        } catch (UnknownHostException e) {
            readAll();
            Log.e("port:UnknownHost", e.getMessage());
            throw new ClientException(String.format("can't figure out the host(%s)", username));
        }
        // 我也不懂这是干嘛的
        @SuppressLint("DefaultLocale")
        String command = String.format("PORT %s,%d,%d", localAddress.replace('.', ','), p1, p2);
        try {
            write(command);
            String resp = controlSocketReader.readLine();
            if (resp == null || !resp.startsWith("200")) {
                readAll();
                throw new ClientException(resp);
            }
        } catch (IOException e) {
            readAll();
            Log.e("port:IOException", e.getMessage());
            throw new ClientException(e.getMessage());
        }
        clientPattern = ClientPattern.ACTIVE;
    }

    /**
     *
     * @param newTypeOfCode 新设置的编码
     * @throws ClientException IOException，奇怪的服务器响应
     */
    public synchronized void type(TypeOfCode newTypeOfCode) throws ClientException {
        readAll();
        String type = (newTypeOfCode == TypeOfCode.ASCII) ? "A" : "B";
        try {
            write(String.format("TYPE %s", type));
            String resp = controlSocketReader.readLine();
            if (resp == null || !resp.startsWith("200")) {
                readAll();
                Log.e("type", "wrong response from server");
                throw new ClientException("wrong response from server");
            }
        } catch (IOException e) {
            readAll();
            Log.e("type:IOException", e.getMessage());
            throw new ClientException(e.getMessage());
        }
        this.typeOfCode = newTypeOfCode;
    }

    /**
     *
     * @throws ClientException 响应不规范，IOException
     */
    public synchronized void noop() throws ClientException {
        readAll();
        try {
            write("NOOP");
            String resp = controlSocketReader.readLine();
            if (resp == null || !resp.startsWith("200")) {
                readAll();
                Log.e("noop", "Illegal response");
                throw new ClientException("Illegal response");
            }
        } catch (IOException e) {
            readAll();
            Log.e("noop:IOException", e.getMessage());
            throw new ClientException(e.getMessage());
        }
    }


    // 工具方法
    void readAll() {
        try {
            while (controlSocketReader.ready()) {
                controlSocketReader.read();
            }
        } catch (IOException e) {
            Log.e("readAll: IOException", e.getMessage());
        }
    }

    /**
     * 向服务器发消息
     * @param content 写入内容
     */
    void write(String content) throws IOException {
        controlSocketWriter.write(content);
        controlSocketWriter.write("\r\n");
        controlSocketWriter.flush();
    }

    String getLocalIPAddr() throws UnknownHostException {
        InetAddress address = InetAddress.getLocalHost();
        return address.getHostAddress();
    }

}
