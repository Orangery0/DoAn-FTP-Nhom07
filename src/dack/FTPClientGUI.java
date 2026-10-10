package dack;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class FTPClientGUI extends JFrame {

    private JTextField txtServerIp = new JTextField("10.69.220.184", 10);
    private JTextField txtUser = new JTextField("admin", 6);
    private JPasswordField txtPass = new JPasswordField("admin123", 6);
    private JComboBox<String> cbMode = new JComboBox<>(new String[]{"Passive Mode", "Active Mode"});

    private DefaultListModel<String> fileListModel = new DefaultListModel<>();
    private JList<String> fileList = new JList<>(fileListModel);

    private JProgressBar progressBar = new JProgressBar(0, 100);
    private JLabel lblSpeed = new JLabel("0.00 MB/s");
    private JTextArea txtLog = new JTextArea(8, 50);
    private JLabel lblStatus = new JLabel("Chua ket noi");

    private Socket controlSocket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private Socket dataSocketToRead = null;

    public FTPClientGUI() {
        setTitle("FTP Client Pro - Nhom 07");
        setSize(850, 600);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel topPanel = new JPanel();
        JButton btnConnect = new JButton("Ket noi (PORT 2121)");
        topPanel.add(new JLabel("IP Server:")); topPanel.add(txtServerIp);
        topPanel.add(new JLabel("Tai khoan:")); topPanel.add(txtUser);
        topPanel.add(new JLabel("Mat khau:")); topPanel.add(txtPass);
        topPanel.add(cbMode);
        topPanel.add(btnConnect);
        add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        JPanel filePanel = new JPanel(new BorderLayout());
        filePanel.setBorder(BorderFactory.createTitledBorder("Danh sach tep tin tren Server (LIST)"));
        filePanel.add(new JScrollPane(fileList), BorderLayout.CENTER);

        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Nhat ky lenh FTP (Control Channel)"));
        txtLog.setEditable(false);
        txtLog.setBackground(Color.BLACK);
        txtLog.setForeground(Color.GREEN);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 12));
        logPanel.add(new JScrollPane(txtLog), BorderLayout.CENTER);

        centerPanel.add(filePanel);
        centerPanel.add(logPanel);
        add(centerPanel, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        JPanel actionPanel = new JPanel();
        JButton btnList = new JButton("Lam moi (LIST)");
        JButton btnDownload = new JButton("Tai ve (RETR)");
        JButton btnUpload = new JButton("Tai len (STOR)");
        JButton btnVerify = new JButton("Check SHA-256");

        actionPanel.add(btnList);
        actionPanel.add(btnDownload);
        actionPanel.add(btnUpload);
        actionPanel.add(btnVerify);

        JPanel progressPanel = new JPanel(new BorderLayout());
        progressBar.setStringPainted(true);
        progressPanel.add(lblStatus, BorderLayout.WEST);
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(lblSpeed, BorderLayout.EAST);

        bottomPanel.add(actionPanel, BorderLayout.NORTH);
        bottomPanel.add(progressPanel, BorderLayout.SOUTH);
        add(bottomPanel, BorderLayout.SOUTH);

        btnConnect.addActionListener(e -> connectServer());
        btnList.addActionListener(e -> refreshFileList());
        btnDownload.addActionListener(e -> downloadFile());
        btnUpload.addActionListener(e -> uploadFile());
        btnVerify.addActionListener(e -> verifyHash());
    }

    private void logCmd(String type, String cmd) {
        SwingUtilities.invokeLater(() -> {
            txtLog.append("[" + type + "] " + cmd + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    private String sendCommand(String cmd) throws IOException {
        logCmd("CLIENT", cmd);
        writer.write(cmd + "\r\n");
        writer.flush();
        String resp = reader.readLine();
        logCmd("SERVER", resp);
        return resp;
    }

    private void connectServer() {
        try {
            controlSocket = new Socket(txtServerIp.getText().trim(), 2121);
            reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.UTF_8));

            logCmd("SERVER", reader.readLine());
            sendCommand("USER " + txtUser.getText().trim());
            String res = sendCommand("PASS " + new String(txtPass.getPassword()));

            lblStatus.setText(res);
            if (res.startsWith("230")) {
                refreshFileList();
            } else {
                JOptionPane.showMessageDialog(this, res);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Loi ket noi: " + ex.getMessage());
        }
    }

    private void setupDataChannel() throws Exception {
        if (cbMode.getSelectedItem().equals("Passive Mode")) {
            String resp = sendCommand("PASV");
            String sub = resp.substring(resp.indexOf("(") + 1, resp.indexOf(")"));
            String[] p = sub.split(",");
            String ip = p[0] + "." + p[1] + "." + p[2] + "." + p[3];
            int port = Integer.parseInt(p[4]) * 256 + Integer.parseInt(p[5]);
            dataSocketToRead = new Socket(ip, port);
        } else {
            ServerSocket activeServer = new ServerSocket(0);
            String localIp = controlSocket.getLocalAddress().getHostAddress().replace(".", ",");
            int localPort = activeServer.getLocalPort();
            sendCommand("PORT " + localIp + "," + (localPort / 256) + "," + (localPort % 256));

            new Thread(() -> {
                try {
                    dataSocketToRead = activeServer.accept();
                    activeServer.close();
                } catch (IOException ignored) {}
            }).start();
            Thread.sleep(200);
        }
    }

    private String getSelectedFileName() {
        String selected = fileList.getSelectedValue();
        if (selected == null) return null;
        int lastDash = selected.lastIndexOf(" - ");
        return lastDash != -1 ? selected.substring(0, lastDash) : selected;
    }

    private void refreshFileList() {
        new Thread(() -> {
            try {
                setupDataChannel();
                sendCommand("LIST");
                BufferedReader dataReader = new BufferedReader(new InputStreamReader(dataSocketToRead.getInputStream(), StandardCharsets.UTF_8));

                SwingUtilities.invokeLater(() -> fileListModel.clear());
                String line;
                while ((line = dataReader.readLine()) != null) {
                    int sepIndex = line.lastIndexOf("|");
                    if (sepIndex != -1) {
                        String name = line.substring(0, sepIndex);
                        long size = Long.parseLong(line.substring(sepIndex + 1));
                        String display = name + " - " + (size / 1024) + " KB";
                        SwingUtilities.invokeLater(() -> fileListModel.addElement(display));
                    }
                }
                dataReader.close();
                dataSocketToRead.close();
                logCmd("SERVER", reader.readLine());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }).start();
    }

    private void downloadFile() {
        String fileName = getSelectedFileName();
        if (fileName == null) {
            JOptionPane.showMessageDialog(this, "Vui long chon 1 file tu danh sach de Tai ve.");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(fileName));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File targetFile = chooser.getSelectedFile();
            new Thread(() -> {
                try {
                    setupDataChannel();
                    String resp150 = sendCommand("RETR " + fileName);
                    if (!resp150.startsWith("150")) return;

                    long expectedSize = Long.parseLong(resp150.substring(resp150.indexOf("(") + 1, resp150.indexOf(" bytes")));

                    try (InputStream in = dataSocketToRead.getInputStream();
                         FileOutputStream fos = new FileOutputStream(targetFile)) {

                        byte[] buffer = new byte[65536];
                        int count;
                        long totalRead = 0;
                        long startTime = System.currentTimeMillis();

                        while ((count = in.read(buffer)) != -1) {
                            fos.write(buffer, 0, count);
                            totalRead += count;

                            int percent = (int) ((totalRead * 100) / (expectedSize > 0 ? expectedSize : 1));
                            long currentTime = System.currentTimeMillis();
                            double speed = (totalRead / 1024.0 / 1024.0) / ((currentTime - startTime + 1) / 1000.0);

                            SwingUtilities.invokeLater(() -> {
                                progressBar.setValue(percent);
                                lblSpeed.setText(String.format("%.2f MB/s", speed));
                            });
                        }
                        fos.flush();
                    }
                    logCmd("SERVER", reader.readLine());
                    JOptionPane.showMessageDialog(this, "Tai ve hoan tat!");
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }).start();
        }
    }

    private void uploadFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            new Thread(() -> {
                try {
                    setupDataChannel();
                    String resp150 = sendCommand("STOR " + file.getName());
                    if (!resp150.startsWith("150")) {
                        JOptionPane.showMessageDialog(this, resp150);
                        return;
                    }

                    try (OutputStream out = dataSocketToRead.getOutputStream();
                         FileInputStream fis = new FileInputStream(file)) {

                        byte[] buffer = new byte[65536];
                        int count;
                        long totalSent = 0;
                        long fileLength = file.length();
                        long startTime = System.currentTimeMillis();

                        while ((count = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, count);
                            totalSent += count;

                            int percent = (int) ((totalSent * 100) / (fileLength > 0 ? fileLength : 1));
                            long currentTime = System.currentTimeMillis();
                            double speed = (totalSent / 1024.0 / 1024.0) / ((currentTime - startTime + 1) / 1000.0);

                            SwingUtilities.invokeLater(() -> {
                                progressBar.setValue(percent);
                                lblSpeed.setText(String.format("%.2f MB/s", speed));
                            });
                        }
                        out.flush();
                    }
                    logCmd("SERVER", reader.readLine());
                    refreshFileList();
                    JOptionPane.showMessageDialog(this, "Tai len thanh cong!");
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }).start();
        }
    }

    private void verifyHash() {
        String fileName = getSelectedFileName();
        if (fileName == null) {
            JOptionPane.showMessageDialog(this, "Vui long nhap chon 1 file trong danh sach phia tren truoc khi check SHA-256!");
            return;
        }
        try {
            String resp = sendCommand("HASH " + fileName);
            JOptionPane.showMessageDialog(this, "Toan ven tep tin (SHA-256):\n" + resp.replace("200 SHA256:", ""));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FTPClientGUI().setVisible(true));
    }
}