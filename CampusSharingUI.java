import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * CampusSharingUI.java
 *
 * Adds:
 *  - Owner Email field on add resource (required for chat).
 *  - Chat support (local, serialized): Chat with Owner & Conversations list.
 *
 * How to run:
 * 1) Save as CampusSharingUI.java
 * 2) Compile: javac CampusSharingUI.java
 * 3) Run:     java CampusSharingUI
 *
 * Note:
 * - Email verification is simulated.
 * - Chat is local-only (no network). Messages serialized to data/chats.ser
 */

public class CampusSharingUI extends JFrame {

    private static final Set<String> ADMINS = new HashSet<>(Arrays.asList(
            "admin@campus.edu",
            "superadmin@campus.edu"
    ));

    // ===== Data models =====
    static class Resource implements Serializable {
        private static final long serialVersionUID = 2L;
        String id;
        String name;
        String owner;         // owner display name
        String ownerContact;  // phone
        String ownerEmail;    // new: owner email for chat (may be null)
        boolean available;

        Resource(String id, String name, String owner, String ownerContact, String ownerEmail) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.ownerContact = ownerContact;
            this.ownerEmail = ownerEmail;
            this.available = true;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s — %s (%s) — %s", id, name, owner, ownerEmail != null ? ownerEmail : ownerContact, available ? "Available" : "Taken");
        }
    }

    // Chat message model
    static class ChatMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        String fromEmail;
        String toEmail;
        long timestamp;
        String text;

        ChatMessage(String from, String to, String text) {
            this.fromEmail = from;
            this.toEmail = to;
            this.text = text;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // Chat storage: map conversationId -> list of messages
    static class ChatStore {
        private final File file;
        Map<String, List<ChatMessage>> convos = new HashMap<>();

        ChatStore(String path) {
            file = new File(path);
            File p = file.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            load();
        }

        private synchronized void load() {
            if (!file.exists()) return;
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                Object o = ois.readObject();
                if (o instanceof Map) {
                    //noinspection unchecked
                    convos = (Map<String, List<ChatMessage>>) o;
                }
            } catch (Exception e) {
                e.printStackTrace();
                // ignore - start fresh
            }
        }

        private synchronized void save() {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
                oos.writeObject(convos);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // conversation id is canonical: sorted emails joined by "::"
        static String convoId(String a, String b) {
            if (a == null || b == null) return null;
            if (a.compareToIgnoreCase(b) <= 0) return a.toLowerCase() + "::" + b.toLowerCase();
            return b.toLowerCase() + "::" + a.toLowerCase();
        }

        synchronized List<ChatMessage> getConversation(String a, String b) {
            String id = convoId(a, b);
            if (id == null) return new ArrayList<>();
            return convos.computeIfAbsent(id, k -> new ArrayList<>());
        }

        synchronized void appendMessage(ChatMessage m) {
            String id = convoId(m.fromEmail, m.toEmail);
            if (id == null) return;
            List<ChatMessage> list = convos.computeIfAbsent(id, k -> new ArrayList<>());
            list.add(m);
            save();
        }

        synchronized List<String> listConversationsFor(String email) {
            List<String> res = new ArrayList<>();
            if (email == null) return res;
            String low = email.toLowerCase();
            for (String id : convos.keySet()) {
                String[] parts = id.split("::");
                if (parts.length == 2 && (parts[0].equals(low) || parts[1].equals(low))) {
                    // other participant:
                    String other = parts[0].equals(low) ? parts[1] : parts[0];
                    res.add(other);
                }
            }
            return res;
        }
    }

    // ===== Persistence for resources =====
    static class ResourceStore {
        private final File file;
        ResourceStore(String path) {
            file = new File(path);
            File p = file.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
        }

        synchronized void save(List<Resource> list) {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
                oos.writeObject(list);
            } catch (IOException e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "Error saving data: " + e.getMessage(), "Save Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        @SuppressWarnings("unchecked")
        synchronized List<Resource> load() {
            if (!file.exists()) return new ArrayList<>();
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                Object o = ois.readObject();
                if (o instanceof List) return (List<Resource>) o;
                return new ArrayList<>();
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "Error loading data: " + e.getMessage(), "Load Error", JOptionPane.ERROR_MESSAGE);
                return new ArrayList<>();
            }
        }
    }

    // ===== UI components =====
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextField tfName = new JTextField(16);
    private final JTextField tfOwner = new JTextField(12);
    private final JTextField tfOwnerContact = new JTextField(12);
    private final JTextField tfOwnerEmail = new JTextField(20); // NEW: owner email field
    private final JButton btnAdd = new JButton("Add Resource");
    private final JButton btnRequest = new JButton("Request (Borrow)");
    private final JButton btnReturn = new JButton("Return");
    private final JButton btnSave = new JButton("Save");
    private final JButton btnLoad = new JButton("Load");
    private final JButton btnDelete = new JButton("Delete Resource");
    private final JButton btnChat = new JButton("Chat with Owner"); // NEW
    private final JButton btnConvos = new JButton("Conversations"); // NEW
    private final JLabel lblStatus = new JLabel("Ready");

    // Data & store
    private final List<Resource> resources = new ArrayList<>();
    private final ResourceStore store = new ResourceStore("data/resources.ser");
    private final ChatStore chatStore = new ChatStore("data/chats.ser");
    private int idCounter = 1000;

    // Session
    private String currentUserEmail = null;
    private boolean isAdminMode = false;

    public CampusSharingUI() { this(false); }
    public CampusSharingUI(boolean isAdmin) {
        super(isAdmin ? "Campus Resource Sharing — Admin View" : "Campus Resource Sharing — User View");
        this.isAdminMode = isAdmin;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(980, 560);
        setLocationRelativeTo(null);

        // Table
        String[] cols = {"ID", "Name", "Owner", "Contact", "Owner Email", "Available"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 5) return Boolean.class;
                return String.class;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(table);

        // Form
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6,6,6,6);
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.LINE_END; form.add(new JLabel("Resource Name:"), c);
        c.gridx = 1; c.anchor = GridBagConstraints.LINE_START; form.add(tfName, c);

        c.gridx = 0; c.gridy = 1; c.anchor = GridBagConstraints.LINE_END; form.add(new JLabel("Owner Name:"), c);
        c.gridx = 1; c.anchor = GridBagConstraints.LINE_START; form.add(tfOwner, c);

        c.gridx = 0; c.gridy = 2; c.anchor = GridBagConstraints.LINE_END; form.add(new JLabel("Owner Contact:"), c);
        c.gridx = 1; c.anchor = GridBagConstraints.LINE_START; form.add(tfOwnerContact, c);

        c.gridx = 0; c.gridy = 3; c.anchor = GridBagConstraints.LINE_END; form.add(new JLabel("Owner Email:"), c);
        c.gridx = 1; c.anchor = GridBagConstraints.LINE_START; form.add(tfOwnerEmail, c);

        c.gridx = 0; c.gridy = 4; c.gridwidth = 2; c.anchor = GridBagConstraints.CENTER; form.add(btnAdd, c);

        // Actions
        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        actions.setBorder(BorderFactory.createTitledBorder("Actions"));
        btnRequest.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnReturn.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnSave.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnLoad.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnDelete.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnChat.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnConvos.setAlignmentX(Component.CENTER_ALIGNMENT);
        actions.add(Box.createVerticalStrut(10));
        actions.add(btnRequest);
        actions.add(Box.createVerticalStrut(8));
        actions.add(btnReturn);
        actions.add(Box.createVerticalStrut(8));
        actions.add(btnChat);
        actions.add(Box.createVerticalStrut(8));
        actions.add(btnConvos);
        actions.add(Box.createVerticalStrut(8));
        actions.add(btnSave);
        actions.add(Box.createVerticalStrut(8));
        actions.add(btnLoad);
        actions.add(Box.createVerticalStrut(8));
        actions.add(btnDelete);
        actions.add(Box.createVerticalGlue());

        JPanel top = new JPanel(new BorderLayout(10,10));
        top.add(form, BorderLayout.WEST);
        top.add(actions, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(lblStatus, BorderLayout.WEST);

        JPanel center = new JPanel(new BorderLayout(10,10));
        center.add(top, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);
        center.add(bottom, BorderLayout.SOUTH);
        add(center);

        // load resources
        loadResourcesOnStart();

        // events
        btnAdd.addActionListener(e -> onAdd());
        btnRequest.addActionListener(e -> borrowSelected());
        btnReturn.addActionListener(e -> returnSelected());
        btnSave.addActionListener(e -> onSave());
        btnLoad.addActionListener(e -> onLoad());
        btnDelete.addActionListener(e -> onDelete());
        btnChat.addActionListener(e -> chatWithOwnerForSelected());
        btnConvos.addActionListener(e -> showConversationsForCurrentUser());

        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int r = table.getSelectedRow();
                    if (r >= 0) {
                        Object availObj = tableModel.getValueAt(r, 5);
                        boolean avail = (availObj instanceof Boolean) ? (Boolean) availObj : Boolean.parseBoolean(String.valueOf(availObj));
                        if (avail) borrowSelected(); else returnSelected();
                    }
                }
            }
        });

        table.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE) {
                    if (isAdminMode) {
                        int sel = table.getSelectedRow();
                        if (sel >= 0) removeSelected();
                    } else {
                        JOptionPane.showMessageDialog(CampusSharingUI.this, "Delete is allowed only for admins.", "Permission denied", JOptionPane.WARNING_MESSAGE);
                    }
                }
            }
        });

        applyRoleSettings();
    }

    // ====== Actions / helpers ======
    private void onAdd() {
        String name = tfName.getText().trim();
        String owner = tfOwner.getText().trim();
        String contact = tfOwnerContact.getText().trim();
        String oemail = tfOwnerEmail.getText().trim();
        if (name.isEmpty() || owner.isEmpty() || contact.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter resource name, owner and owner contact.", "Input required", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!contact.matches("[0-9+\\- ]{6,20}")) {
            JOptionPane.showMessageDialog(this, "Enter a valid contact number (digits, +, - allowed).", "Invalid Contact", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!oemail.isEmpty() && !LoginDialog.isPlausibleEmail(oemail)) {
            JOptionPane.showMessageDialog(this, "Owner email looks invalid. Leave empty or enter valid email.", "Invalid Email", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String id = String.valueOf(idCounter++);
        Resource r = new Resource(id, name, owner, contact, oemail.isEmpty() ? null : oemail.toLowerCase());
        resources.add(r);
        addRow(r);
        tfName.setText(""); tfOwner.setText(""); tfOwnerContact.setText(""); tfOwnerEmail.setText("");
        lblStatus.setText("Added resource: " + r.name);
    }

    private void onSave() {
        store.save(resources);
        lblStatus.setText("Saved " + resources.size() + " resources.");
    }

    private void onLoad() {
        int confirm = JOptionPane.showConfirmDialog(this, "Load will replace current list. Continue?", "Load Confirmation", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        List<Resource> loaded = store.load();
        resources.clear(); resources.addAll(loaded);
        rebuildTable();
        loaded.stream().map(r -> {
            try { return Integer.parseInt(r.id); } catch (Exception e) { return 0; }
        }).max(Integer::compareTo).ifPresent(max -> idCounter = Math.max(idCounter, max + 1));
        lblStatus.setText("Loaded " + resources.size() + " resources.");
    }

    private void onDelete() {
        if (!isAdminMode) {
            JOptionPane.showMessageDialog(this, "Only admins can delete resources.", "Permission denied", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int sel = table.getSelectedRow();
        if (sel < 0) { JOptionPane.showMessageDialog(this, "Select a resource first.", "No selection", JOptionPane.INFORMATION_MESSAGE); return; }
        removeSelected();
    }

    private void loadResourcesOnStart() {
        List<Resource> loaded = store.load();
        if (!loaded.isEmpty()) {
            resources.addAll(loaded);
            loaded.forEach(this::addRow);
            loaded.stream().map(r -> {
                try { return Integer.parseInt(r.id); } catch (Exception e) { return 0; }
            }).max(Integer::compareTo).ifPresent(max -> idCounter = Math.max(idCounter, max + 1));
            lblStatus.setText("Loaded " + resources.size() + " resources from storage.");
        } else {
            lblStatus.setText("No saved resources. Ready.");
        }
    }

    private void addRow(Resource r) {
        tableModel.addRow(new Object[]{r.id, r.name, r.owner, r.ownerContact, r.ownerEmail != null ? r.ownerEmail : "", r.available});
    }

    private void rebuildTable() {
        tableModel.setRowCount(0);
        for (Resource r : resources) addRow(r);
    }

    private Resource findById(String id) {
        for (Resource r : resources) if (r.id.equals(id)) return r;
        return null;
    }

    private void borrowSelected() {
        if (currentUserEmail == null) {
            JOptionPane.showMessageDialog(this, "Login required to borrow a resource.", "Login Required", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int sel = table.getSelectedRow();
        if (sel < 0) { JOptionPane.showMessageDialog(this, "Select a resource first.", "No selection", JOptionPane.INFORMATION_MESSAGE); return; }
        String id = (String) tableModel.getValueAt(sel, 0);
        Resource r = findById(id);
        if (r == null) return;
        if (!r.available) { JOptionPane.showMessageDialog(this, "Resource already taken.", "Unavailable", JOptionPane.WARNING_MESSAGE); return; }

        String msg = String.format("You're requesting \"%s\" owned by %s (%s).\nPayment is required to confirm the borrow.", r.name, r.owner, r.ownerContact);
        int cont = JOptionPane.showConfirmDialog(this, msg, "Confirm and Pay", JOptionPane.YES_NO_OPTION);
        if (cont != JOptionPane.YES_OPTION) return;

        PaymentDialog pd = new PaymentDialog(this, r.name);
        boolean paid = pd.runAndReturnSuccess();
        if (!paid) { JOptionPane.showMessageDialog(this, "Payment failed or cancelled. Borrow not completed.", "Payment Failed", JOptionPane.WARNING_MESSAGE); return; }

        r.available = false;
        tableModel.setValueAt(Boolean.valueOf(r.available), sel, 5);
        lblStatus.setText("Requested (paid): " + r.name + " by " + currentUserEmail);
    }

    private void returnSelected() {
        int sel = table.getSelectedRow();
        if (sel < 0) { JOptionPane.showMessageDialog(this, "Select a resource first.", "No selection", JOptionPane.INFORMATION_MESSAGE); return; }
        String id = (String) tableModel.getValueAt(sel, 0);
        Resource r = findById(id);
        if (r == null) return;
        if (r.available) { JOptionPane.showMessageDialog(this, "Resource already available.", "Info", JOptionPane.INFORMATION_MESSAGE); return; }
        int conf = JOptionPane.showConfirmDialog(this, "Return resource \"" + r.name + "\"?", "Confirm Return", JOptionPane.YES_NO_OPTION);
        if (conf != JOptionPane.YES_OPTION) return;
        r.available = true;
        tableModel.setValueAt(Boolean.valueOf(r.available), sel, 5);
        lblStatus.setText("Returned: " + r.name);
    }

    private void removeSelected() {
        int sel = table.getSelectedRow();
        if (sel < 0) return;
        String id = (String) tableModel.getValueAt(sel, 0);
        Resource r = findById(id);
        if (r == null) return;
        int conf = JOptionPane.showConfirmDialog(this, "Delete resource \"" + r.name + "\"?", "Delete", JOptionPane.YES_NO_OPTION);
        if (conf != JOptionPane.YES_OPTION) return;
        resources.remove(r);
        tableModel.removeRow(sel);
        lblStatus.setText("Deleted: " + r.name);
    }

    // ===== Chat features =====

    // Open chat with owner for selected resource
    private void chatWithOwnerForSelected() {
        int sel = table.getSelectedRow();
        if (sel < 0) { JOptionPane.showMessageDialog(this, "Select a resource to chat with its owner.", "No selection", JOptionPane.INFORMATION_MESSAGE); return; }
        String ownerEmail = (String) tableModel.getValueAt(sel, 4);
        if (ownerEmail == null || ownerEmail.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Owner email not provided for this resource. Chat not available.", "Chat unavailable", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (currentUserEmail == null) {
            JOptionPane.showMessageDialog(this, "Login required to chat.", "Login Required", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        openChatWindow(currentUserEmail, ownerEmail.trim().toLowerCase());
    }

    // Show list of conversations for current user
    private void showConversationsForCurrentUser() {
        if (currentUserEmail == null) {
            JOptionPane.showMessageDialog(this, "Login required to view conversations.", "Login Required", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<String> others = chatStore.listConversationsFor(currentUserEmail);
        if (others.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No conversations yet.", "Conversations", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // simple selection dialog
        String sel = (String) JOptionPane.showInputDialog(this, "Select conversation:", "Conversations", JOptionPane.PLAIN_MESSAGE, null, others.toArray(new String[0]), others.get(0));
        if (sel != null) {
            openChatWindow(currentUserEmail, sel);
        }
    }

    // Chat window
    private void openChatWindow(String me, String other) {
        if (me == null || other == null) return;
        ChatDialog cd = new ChatDialog(this, me, other);
        cd.setVisible(true);
    }

    // ===== Roles =====
    private void applyRoleSettings() {
        btnDelete.setEnabled(isAdminMode);
        // chat buttons enabled only when ownerEmail exists / user logged in; but we keep enabled and check on click.
        if (isAdminMode) {
            setTitle("Campus Resource Sharing — Admin View" + (currentUserEmail != null ? " (" + currentUserEmail + ")" : ""));
            lblStatus.setText("Admin mode. You can delete resources.");
        } else {
            setTitle("Campus Resource Sharing — User View" + (currentUserEmail != null ? " (" + currentUserEmail + ")" : ""));
            lblStatus.setText("User mode. Delete disabled.");
        }
    }

    // ===== Dialog classes =====

    // Login dialog unchanged except static isPlausibleEmail used above
    static class LoginDialog extends JDialog {
        private final JTextField tfEmail = new JTextField(24);
        private final JTextField tfCode = new JTextField(10);
        private final JButton btnSend = new JButton("Send code (simulate)");
        private final JButton btnVerify = new JButton("Verify & Login");
        private final JButton btnCancel = new JButton("Cancel");
        private String verifiedEmail = null;
        private String lastCode = null;

        LoginDialog(Frame owner) {
            super(owner, "Login — Email verification", true);
            setLayout(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(8,8,8,8);
            c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.LINE_END;
            add(new JLabel("Email:"), c);
            c.gridx = 1; c.anchor = GridBagConstraints.LINE_START;
            add(tfEmail, c);

            c.gridx = 0; c.gridy = 1; c.anchor = GridBagConstraints.LINE_END;
            add(new JLabel("Verification code:"), c);
            c.gridx = 1; c.anchor = GridBagConstraints.LINE_START;
            add(tfCode, c);

            JPanel row = new JPanel();
            row.add(btnSend);
            row.add(btnVerify);
            row.add(btnCancel);
            c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
            add(row, c);

            btnSend.addActionListener(e -> {
                String email = tfEmail.getText().trim();
                if (!isPlausibleEmail(email)) {
                    JOptionPane.showMessageDialog(this, "Enter a valid email address.", "Invalid Email", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                lastCode = simulateSendVerificationCode(email);
                JOptionPane.showMessageDialog(this, "Verification code (SIMULATED): " + lastCode + "\nIn production, this code would be emailed to the user.", "Simulated send", JOptionPane.INFORMATION_MESSAGE);
            });

            btnVerify.addActionListener(e -> {
                String email = tfEmail.getText().trim();
                String code = tfCode.getText().trim();
                if (email.isEmpty() || code.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Enter email and code.", "Missing", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (lastCode != null && lastCode.equals(code) && isPlausibleEmail(email)) {
                    verifiedEmail = email.toLowerCase();
                    dispose();
                } else {
                    JOptionPane.showMessageDialog(this, "Verification failed. Make sure you clicked 'Send code (simulate)' and entered the correct code.", "Failed", JOptionPane.ERROR_MESSAGE);
                }
            });

            btnCancel.addActionListener(e -> { verifiedEmail = null; dispose(); });

            pack();
            setLocationRelativeTo(owner);
        }

        static boolean isPlausibleEmail(String email) {
            return email.matches("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
        }

        private static String simulateSendVerificationCode(String email) {
            Random rnd = new Random();
            int code = 100000 + rnd.nextInt(900000);
            return String.valueOf(code);
        }

        String runAndGetVerifiedEmail() {
            setVisible(true);
            return verifiedEmail;
        }
    }

    // Chat dialog showing history and input box
    class ChatDialog extends JDialog {
        private final JTextArea taHistory = new JTextArea(18, 50);
        private final JTextField tfInput = new JTextField(40);
        private final JButton btnSend = new JButton("Send");
        private final String me;
        private final String other;
        private final SimpleDateFormat sdf = new SimpleDateFormat("dd-MMM HH:mm");

        ChatDialog(Frame owner, String me, String other) {
            super(owner, "Chat: " + me + " ↔ " + other, true);
            this.me = me.toLowerCase();
            this.other = other.toLowerCase();
            taHistory.setEditable(false);
            taHistory.setLineWrap(true);
            taHistory.setWrapStyleWord(true);

            JPanel p = new JPanel(new BorderLayout(8,8));
            p.add(new JScrollPane(taHistory), BorderLayout.CENTER);

            JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
            inputRow.add(tfInput);
            inputRow.add(btnSend);
            p.add(inputRow, BorderLayout.SOUTH);

            setContentPane(p);
            pack();
            setLocationRelativeTo(owner);

            // load history
            refreshHistory();

            btnSend.addActionListener(e -> {
                String text = tfInput.getText().trim();
                if (text.isEmpty()) return;
                ChatMessage m = new ChatMessage(me, other, text);
                chatStore.appendMessage(m);
                tfInput.setText("");
                refreshHistory();
            });
        }

        private void refreshHistory() {
            List<ChatMessage> msgs = chatStore.getConversation(me, other);
            StringBuilder sb = new StringBuilder();
            for (ChatMessage m : msgs) {
                String who = m.fromEmail.equalsIgnoreCase(me) ? "You" : m.fromEmail;
                String time = sdf.format(new Date(m.timestamp));
                sb.append(String.format("%s [%s]: %s%n", who, time, m.text));
            }
            taHistory.setText(sb.toString());
            taHistory.setCaretPosition(taHistory.getDocument().getLength());
        }
    }

    // Payment dialog same as before
    static class PaymentDialog extends JDialog {
        private final JTextField tfAmount = new JTextField("50", 8); // default
        private final JTextField tfNote = new JTextField(20);
        private final JButton btnPay = new JButton("Pay (simulate)");
        private final JButton btnCancel = new JButton("Cancel");
        private boolean success = false;
        private final String resourceName;

        PaymentDialog(Frame owner, String resourceName) {
            super(owner, "Payment for: " + resourceName, true);
            this.resourceName = resourceName;
            setLayout(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(8,8,8,8);
            c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.LINE_END;
            add(new JLabel("Amount (INR):"), c);
            c.gridx = 1; c.anchor = GridBagConstraints.LINE_START;
            add(tfAmount, c);

            c.gridx = 0; c.gridy = 1; c.anchor = GridBagConstraints.LINE_END;
            add(new JLabel("Note:"), c);
            c.gridx = 1; c.anchor = GridBagConstraints.LINE_START;
            tfNote.setText("Borrow fee for " + resourceName);
            add(tfNote, c);

            JPanel row = new JPanel();
            row.add(btnPay);
            row.add(btnCancel);
            c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
            add(row, c);

            btnPay.addActionListener(e -> {
                String amtStr = tfAmount.getText().trim();
                double amt;
                try {
                    amt = Double.parseDouble(amtStr);
                    if (amt <= 0) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(this, "Enter a valid positive amount.", "Invalid Amount", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                boolean ok = pay(amt, tfNote.getText().trim());
                if (ok) {
                    success = true;
                    JOptionPane.showMessageDialog(this, "Payment successful (SIMULATED).", "Payment", JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } else {
                    JOptionPane.showMessageDialog(this, "Payment failed (SIMULATED). Try again or cancel.", "Payment Failed", JOptionPane.ERROR_MESSAGE);
                }
            });

            btnCancel.addActionListener(e -> { success = false; dispose(); });

            pack();
            setLocationRelativeTo(owner);
        }

        private boolean pay(double amount, String note) {
            return new Random().nextDouble() < 0.9;
        }

        boolean runAndReturnSuccess() {
            setVisible(true);
            return success;
        }
    }

    // ===== main =====
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}

            // small invisible owner frame for modal dialogs
            JFrame tmp = new JFrame();
            tmp.setUndecorated(true);
            tmp.setSize(0,0);
            tmp.setLocationRelativeTo(null);
            tmp.setVisible(false);

            LoginDialog ld = new LoginDialog(tmp);
            String verified = ld.runAndGetVerifiedEmail();
            if (verified == null) {
                JOptionPane.showMessageDialog(null, "Login required to use the app. Exiting.", "Login required", JOptionPane.WARNING_MESSAGE);
                System.exit(0);
            } else {
                boolean isAdmin = ADMINS.contains(verified.toLowerCase());
                CampusSharingUI app = new CampusSharingUI(isAdmin);
                app.currentUserEmail = verified.toLowerCase();
                app.applyRoleSettings();
                app.lblStatus.setText((isAdmin ? "Admin" : "User") + " logged in as: " + verified);
                app.setVisible(true);
                tmp.dispose();
            }
        });
    }
}