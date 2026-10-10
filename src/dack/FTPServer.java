package dack;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FTPServer {
    private static final int CONTROL_PORT = 2121;
    private static final String STORAGE_DIR = "D:\\server_storage\\";
    private static final Set<String> lockedFiles = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) {
        File dir = new File(STORAGE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
            System.out.println("-> Da tu dong tao thu muc luu tru tai: " + dir.getAbsolutePath());
        }

        System.out.println("=== FTP SERVER NHOM 07 DANG CHAY TAI PORT " + CONTROL_PORT + " ===");
        try (ServerSocket serverSocket = new ServerSocket(CONTROL_PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                new Thread(new ClientHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ClientHandler implements Runnable {
        private Socket controlSocket;
        private BufferedReader reader;
        private BufferedWriter writer;
        private String currentUser = null;
        private boolean isLoggedIn = false;

        private boolean isPassiveMode = false;
        private ServerSocket passiveServerSocket = null;
        private String activeClientIp = null;
        private int activeClientPort = 0;

        public ClientHandler(Socket socket) {
            this.controlSocket = socket;
        }

        private void sendResponse(String msg) throws IOException {
            writer.write(msg + "\r\n");
            writer.flush();
        }

        @Override
        public void run() {
            try {
                reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.UTF_8));

                sendResponse("220 FTP Server Nhom 07 (RFC 959) San Sang.");

                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[Client gui]: " + line);
                    String[] parts = line.trim().split("\\s+", 2);
                    String command = parts[0].toUpperCase();
                    String arg = parts.length > 1 ? parts[1] : "";

                    switch (command) {
                        case "USER":
                            currentUser = arg;
                            sendResponse("331 Can nhap mat khau cho " + currentUser);
                            break;

                        case "PASS":
                            if (("admin".equals(currentUser) && "admin123".equals(arg)) ||
                                ("user".equals(currentUser) && "123".equals(arg))) {
                                isLoggedIn = true;
                                sendResponse("230 Dang nhap thanh cong.");
                            } else {
                                sendResponse("530 Dang nhap that bai. Sai tai khoan hoac mat khau.");
                            }
                            break;

                        case "PASV":
                            if (!isLoggedIn) { sendResponse("530 Chua dang nhap."); break; }
                            isPassiveMode = true;
                            if (passiveServerSocket != null && !passiveServerSocket.isClosed()) {
                                passiveServerSocket.close();
                            }

                            passiveServerSocket = new ServerSocket(0);
                            passiveServerSocket.setSoTimeout(30000);
                            int assignedPort = passiveServerSocket.getLocalPort();
                            String serverIp = controlSocket.getLocalAddress().getHostAddress().replace(".", ",");
                            sendResponse("227 Chuyen sang Passive Mode (" + serverIp + "," + (assignedPort / 256) + "," + (assignedPort % 256) + ").");
                            break;

                        case "PORT":
                            if (!isLoggedIn) { sendResponse("530 Chua dang nhap."); break; }
                            isPassiveMode = false;
                            String[] ipPorts = arg.split(",");
                            if (ipPorts.length < 6) {
                                sendResponse("501 Cu phap lenh PORT khong hop le.");
                                break;
                            }
                            activeClientIp = ipPorts[0] + "." + ipPorts[1] + "." + ipPorts[2] + "." + ipPorts[3];
                            activeClientPort = Integer.parseInt(ipPorts[4]) * 256 + Integer.parseInt(ipPorts[5]);
                            sendResponse("200 Lenh PORT thanh cong.");
                            break;

                        case "LIST":
                            if (!isLoggedIn) { sendResponse("530 Chua dang nhap."); break; }
                            sendResponse("150 Mo kenh du lieu truyen danh sach file.");
                            try (Socket dataSocket = openDataSocket();
                                 BufferedWriter dataWriter = new BufferedWriter(new OutputStreamWriter(dataSocket.getOutputStream(), StandardCharsets.UTF_8))) {
                                File folder = new File(STORAGE_DIR);
                                File[] listOfFiles = folder.listFiles();
                                if (listOfFiles != null) {
                                    for (File file : listOfFiles) {
                                        if (file.isFile()) {
                                            dataWriter.write(file.getName() + " | " + file.length() + " bytes\r\n");
                                        }
                                    }
                                    dataWriter.flush();
                                }
                            }
                            sendResponse("226 Hoan tat truyen danh sach.");
                            break;

                        case "RETR":
                            if (!isLoggedIn) { sendResponse("530 Chua dang nhap."); break; }
                            File downloadFile = new File(STORAGE_DIR + arg);
                            if (!downloadFile.exists()) { sendResponse("550 File khong ton tai."); break; }

                            sendResponse("150 Bat dau truyen file " + arg + " (" + downloadFile.length() + " bytes)");
                            try (Socket dataSocket = openDataSocket();
                                 FileInputStream fis = new FileInputStream(downloadFile);
                                 OutputStream out = dataSocket.getOutputStream()) {
                                byte[] buffer = new byte[65536];
                                int count;
                                while ((count = fis.read(buffer)) != -1) {
                                    out.write(buffer, 0, count);
                                }
                                out.flush();
                            }
                            sendResponse("226 Truyen tai thanh cong.");
                            break;

                        case "STOR":
                            if (!isLoggedIn) { sendResponse("530 Chua dang nhap."); break; }
                            if ("user".equals(currentUser)) {
                                sendResponse("550 Tai khoan user khong co quyen upload.");
                                break;
                            }
                            if (!lockedFiles.add(arg)) {
                                sendResponse("450 File dang duoc nguoi khac thao tac, vui long thu lai sau.");
                                break;
                            }
                            sendResponse("150 Kenh du lieu san sang nhan file.");
                            try (Socket dataSocket = openDataSocket();
                                 FileOutputStream fos = new FileOutputStream(STORAGE_DIR + arg);
                                 InputStream in = dataSocket.getInputStream()) {
                                byte[] buffer = new byte[65536];
                                int count;
                                while ((count = in.read(buffer)) != -1) {
                                    fos.write(buffer, 0, count);
                                }
                                fos.flush();
                            } finally {
                                lockedFiles.remove(arg);
                            }
                            sendResponse("226 Luu file tren server thanh cong.");
                            break;

                        case "HASH":
                            if (!isLoggedIn) { sendResponse("530 Chua dang nhap."); break; }
                            File hashFile = new File(STORAGE_DIR + arg);
                            if (hashFile.exists()) {
                                String checksum = getFileChecksum(hashFile);
                                sendResponse("200 SHA256:" + checksum);
                            } else {
                                sendResponse("550 Khong tim thay file de tinh hash.");
                            }
                            break;

                        case "QUIT":
                            sendResponse("221 Tam biet.");
                            controlSocket.close();
                            return;

                        default:
                            sendResponse("502 Lenh khong duoc ho tro.");
                    }
                }
            } catch (Exception e) {
                System.out.println("[Server Log] Ngat ket noi voi client do loi: " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        private String getFileChecksum(File file) {
            try (FileInputStream fis = new FileInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] byteArray = new byte[1024];
                int bytesCount;
                while ((bytesCount = fis.read(byteArray)) != -1) {
                    digest.update(byteArray, 0, bytesCount);
                }
                byte[] bytes = digest.digest();
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes) {
                    sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
                }
                return sb.toString();
            } catch (Exception e) {
                return "ERROR";
            }
        }

        private Socket openDataSocket() throws IOException {
            if (isPassiveMode) {
                if (passiveServerSocket == null) throw new IOException("Passive socket chua duoc khoi tao.");
                return passiveServerSocket.accept();
            } else {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(activeClientIp, activeClientPort), 5000);
                socket.setTcpNoDelay(true);
                return socket;
            }
        }

        private void cleanup() {
            try {
                if (passiveServerSocket != null && !passiveServerSocket.isClosed()) passiveServerSocket.close();
                if (controlSocket != null && !controlSocket.isClosed()) controlSocket.close();
            } catch (IOException ignored) {}
        }
    }
}