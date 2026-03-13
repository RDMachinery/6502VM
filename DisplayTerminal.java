import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;

/**
 * DisplayTerminal — a Swing-based memory-mapped display for the MOS6502 VM.
 *
 * Memory map
 * ----------
 *   $1000 – $13FF  (1 024 bytes)  display RAM
 *
 * The display is 32 columns × 32 rows (1 024 cells).
 * Each byte is treated as a raw ASCII character code.
 * Byte 0 ($1000) maps to column 0, row 0 (top-left).
 * Byte 31 ($101F) maps to column 31, row 0 (top-right).
 * Byte 32 ($1020) is the first cell of row 1, and so on.
 *
 * Demo program (written at $0600)
 * --------------------------------
 *   Writes 'a'–'z' to screen positions 0–25, then halts with BRK.
 *
 * Compile & run
 * -------------
 *   javac MOS6502.java DisplayTerminal.java
 *   java  DisplayTerminal
 */
public class DisplayTerminal extends MOS6502 {

    // -----------------------------------------------------------------------
    // Display geometry
    // -----------------------------------------------------------------------
    public static final int VRAM_START  = 0x1000;
    public static final int VRAM_SIZE   = 1024;          // 32 × 32
    public static final int COLS        = 32;
    public static final int ROWS        = VRAM_SIZE / COLS;  // 32

    // -----------------------------------------------------------------------
    // Rendering constants  (retro phosphor-green CRT aesthetic)
    // -----------------------------------------------------------------------
    private static final int CELL_W     = 18;   // px per character cell
    private static final int CELL_H     = 26;
    private static final int PADDING    = 24;   // border around the grid

    private static final Color BG_COLOR      = new Color(0x0A, 0x0F, 0x0A);
    private static final Color PHOSPHOR      = new Color(0x33, 0xFF, 0x66);
    private static final Color PHOSPHOR_DIM  = new Color(0x0D, 0x66, 0x22);
    private static final Color BORDER_COLOR  = new Color(0x1A, 0x3A, 0x1A);
    private static final Color SCANLINE_COLOR= new Color(0, 0, 0, 38);

    // -----------------------------------------------------------------------
    // Swing components
    // -----------------------------------------------------------------------
    private final ScreenPanel screenPanel;
    private final JLabel      cycleLabel;
    private final JLabel      pcLabel;
    private       long        totalCycles;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public DisplayTerminal() {
        super(0x10000);

        // --- Screen panel ---
        screenPanel = new ScreenPanel();

        // --- Status bar ---
        cycleLabel = makeStatusLabel("Cycles: 0");
        pcLabel    = makeStatusLabel("PC: $0000");

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
        statusBar.setBackground(new Color(0x06, 0x0C, 0x06));
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR));
        statusBar.add(makeStatusLabel("MOS 6502  |  VRAM $1000–$13FF  |  32×32"));
        statusBar.add(Box.createHorizontalStrut(20));
        statusBar.add(cycleLabel);
        statusBar.add(pcLabel);

        // --- Run button ---
        JButton runBtn = new JButton("▶  RUN DEMO");
        runBtn.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        runBtn.setForeground(PHOSPHOR);
        runBtn.setBackground(new Color(0x06, 0x18, 0x06));
        runBtn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(PHOSPHOR_DIM, 1),
            BorderFactory.createEmptyBorder(6, 18, 6, 18)
        ));
        runBtn.setFocusPainted(false);
        runBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        runBtn.addActionListener(e -> runDemo(runBtn));

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 8));
        topBar.setBackground(new Color(0x06, 0x0C, 0x06));
        topBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));
        topBar.add(runBtn);

        // --- Assemble frame ---
        JFrame frame = new JFrame("MOS 6502  ·  Display Terminal");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(BG_COLOR);
        frame.add(topBar,     BorderLayout.NORTH);
        frame.add(screenPanel, BorderLayout.CENTER);
        frame.add(statusBar,  BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setResizable(false);
        frame.setVisible(true);
    }

    // -----------------------------------------------------------------------
    // Memory-mapped write override — repaint when VRAM changes
    // -----------------------------------------------------------------------
    @Override
    protected void write(int addr, int val) {
        super.write(addr, val);
        if (addr >= VRAM_START && addr < VRAM_START + VRAM_SIZE) {
            SwingUtilities.invokeLater(screenPanel::repaint);
        }
    }

    // -----------------------------------------------------------------------
    // Demo: write 'a'–'z' into VRAM then halt
    // -----------------------------------------------------------------------
    private void runDemo(JButton btn) {
        btn.setEnabled(false);
        totalCycles = 0;

        // Clear VRAM
        for (int i = 0; i < VRAM_SIZE; i++) write(VRAM_START + i, 0x00);

        /*
         * 6502 program — load 'a' into A, write to $1000, increment, loop 26×
         *
         *   LDA #$61        ; A = 'a'
         *   LDX #$00        ; X = 0  (VRAM offset)
         * loop:
         *   STA $1000,X     ; mem[$1000 + X] = A
         *   INX             ; X++
         *   CLC
         *   ADC #$01        ; A++
         *   CPX #$1A        ; X == 26?
         *   BNE loop
         *   BRK
         */
        loadProgram(0x0600,
            0xA9, 0x61,              // LDA #'a'
            0xA2, 0x00,              // LDX #0
            // loop:
            0x9D, 0x00, 0x10,        // STA $1000,X
            0xE8,                    // INX
            0x18,                    // CLC
            0x69, 0x01,              // ADC #1
            0xE0, 0x1A,              // CPX #26
            0xD0, 0xF6,              // BNE loop  (offset = -10)
            0x00                     // BRK
        );

        reset(0x0600);

        // Run on a background thread so Swing stays responsive
        new Thread(() -> {
            while (!isHalted()) {
                totalCycles += step();
                // tiny sleep to let each write animate visibly
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }
            // Final status update on EDT
            SwingUtilities.invokeLater(() -> {
                cycleLabel.setText("Cycles: " + totalCycles);
                pcLabel.setText(String.format("PC: $%04X", PC));
                btn.setEnabled(true);
            });
        }, "6502-runner").start();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private static JLabel makeStatusLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        lbl.setForeground(PHOSPHOR_DIM);
        return lbl;
    }

    // -----------------------------------------------------------------------
    // Screen rendering panel
    // -----------------------------------------------------------------------
    private class ScreenPanel extends JPanel {

        private final int W = COLS * CELL_W + PADDING * 2;
        private final int H = ROWS * CELL_H + PADDING * 2;
        private final Font cellFont = new Font(Font.MONOSPACED, Font.BOLD, 17);

        ScreenPanel() {
            setPreferredSize(new Dimension(W, H));
            setBackground(BG_COLOR);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);

            // Background
            g2.setColor(BG_COLOR);
            g2.fillRect(0, 0, W, H);

            // Outer border glow
            g2.setColor(BORDER_COLOR);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(PADDING / 2, PADDING / 2,
                             W - PADDING, H - PADDING, 6, 6);

            // Draw each character cell
            g2.setFont(cellFont);
            FontMetrics fm = g2.getFontMetrics();

            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int addr = VRAM_START + row * COLS + col;
                    int code = mem[addr] & 0xFF;

                    int cx = PADDING + col * CELL_W;
                    int cy = PADDING + row * CELL_H;

                    if (code != 0x00) {
                        // Faint background tint for occupied cells
                        g2.setColor(new Color(0x00, 0x22, 0x08));
                        g2.fillRect(cx, cy, CELL_W, CELL_H);

                        // Phosphor glow: draw a blurred copy first
                        g2.setColor(PHOSPHOR_DIM);
                        String ch = String.valueOf((char) code);
                        int tx = cx + (CELL_W - fm.stringWidth(ch)) / 2;
                        int ty = cy + CELL_H - (CELL_H - fm.getAscent()) / 2 - 3;
                        g2.drawString(ch, tx - 1, ty - 1);
                        g2.drawString(ch, tx + 1, ty + 1);

                        // Main phosphor character
                        g2.setColor(PHOSPHOR);
                        g2.drawString(ch, tx, ty);
                    } else {
                        // Empty cell cursor-dot
                        g2.setColor(new Color(0x0D, 0x22, 0x11));
                        g2.fillRect(cx + CELL_W / 2 - 1, cy + CELL_H / 2 - 1, 2, 2);
                    }
                }
            }

            // Scanline overlay
            g2.setColor(SCANLINE_COLOR);
            for (int y = 0; y < H; y += 2) {
                g2.drawLine(0, y, W, y);
            }

            // Vignette
            RadialGradientPaint vignette = new RadialGradientPaint(
                new Point(W / 2, H / 2),
                Math.max(W, H) * 0.72f,
                new float[]{0.55f, 1.0f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 130)}
            );
            g2.setPaint(vignette);
            g2.fillRect(0, 0, W, H);

            g2.dispose();
        }
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(DisplayTerminal::new);
    }
}
