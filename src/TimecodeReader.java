import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.prefs.Preferences;

// Reference: https://en.wikipedia.org/wiki/Linear_timecode

public class TimecodeReader extends JFrame implements Runnable {
  private transient Preferences   prefs = Preferences.userRoot().node(this.getClass().getName());
  private boolean                 running, displaying;
  private final JButton           capture;
  private final TimeCode          timecode;
  private JMenu                   inputMenu;
  private Mixer                   selectedMixer;
  private Thread                  runThread;

  private class TimeCode extends JPanel implements Runnable {
    private static final int      SYNC = 0xBFFC;
    private JLabel                timeView;
    private JCheckBox             user59, user58, user27, user43, user11, user10;
    private JLabel                userData, frameRate;
    private JProgressBar          levelMeter;
    private PipedInputStream      pipeIn;
    private PipedOutputStream     pipeOut;
    private AudioFormat           format;
    private boolean               running;
    // AudioFormat-related variables
    private AudioFormat.Encoding  encoding;
    private boolean               bigEndian;
    private int                   channels;
    private int                   frameSize;
    // Timecode decoder variables (holds state between called to process() method)
    private boolean               skipBit = false;
    private boolean               bitValue = false;
    private int[]                 frame = new int[4];
    private int                   bitCount = 0;
    private int                   frameIndex = 0;
    private int                   lastInterval = 0;
    private int                   frameWord = 0;
    private int                   lastSample;
    private int                   interval;
    private int                   lastSecond;
    private int                   frameCount;

    private TimeCode () {
      setLayout(new BorderLayout());
      // Create Timecode Panel
      add(getTitledPanel("Timecode:", timeView = new JLabel()), BorderLayout.NORTH);
      timeView.setFont(new Font("Courier", Font.PLAIN, 60));
      timeView.setVerticalAlignment(SwingConstants.CENTER);
      timeView.setHorizontalAlignment(SwingConstants.CENTER);
      // Creatae User Bits subpanel
      JPanel indicators = new JPanel(new GridLayout(2, 1));
      JPanel userBits = new JPanel(new GridLayout(1, 6));
      userBits.add(user59 = new JCheckBox("Bit 59"));
      userBits.add(user58 = new JCheckBox("Bit 58"));
      userBits.add(user43 = new JCheckBox("Bit 43"));
      userBits.add(user27 = new JCheckBox("Bit 23"));
      userBits.add(user11 = new JCheckBox("Bit 11"));
      userBits.add(user10 = new JCheckBox("Bit 10"));
      indicators.add(userData = new JLabel("- - - -", SwingConstants.CENTER));
      userData.setFont(new Font("Courier", Font.PLAIN, 32));
      indicators.add(userBits);
      add(getTitledPanel("Information Bits:", indicators), BorderLayout.CENTER);
      JPanel southSet = new JPanel(new GridLayout(2, 1));
      // Create Input Level Panel
      JPanel meterPanel = getTitledPanel("Input Level:", levelMeter = new JProgressBar(0, 100));
      meterPanel.setLayout(new BorderLayout());
      levelMeter.setValue(0);
      meterPanel.add(levelMeter, BorderLayout.CENTER);
      southSet.add(getTitledPanel("Estimated Frame Rate:", frameRate = new JLabel("--")));
      southSet.add(meterPanel);
      add(southSet, BorderLayout.SOUTH);
      decodeFrame(new int[4]);
    }

    private JPanel getTitledPanel (String title, JComponent comp) {
      JPanel frame = new JPanel();
      Border gap = BorderFactory.createEmptyBorder(4, 4, 4, 4);
      frame.setBorder(BorderFactory.createCompoundBorder(gap,
                      BorderFactory.createTitledBorder(title)));
      frame.add(comp);
      return frame;
    }

    private void setFormat (AudioFormat format) {
      this.format = format;
      encoding = format.getEncoding();
      bigEndian = format.isBigEndian();
      channels = format.getChannels();
      frameSize = format.getFrameSize();
    }

    private PipedOutputStream getPipeOut () {
      try {
        if (!running) {
          if (format != null) {
            running = true;
            new Thread(this).start();
          }
          pipeOut = new PipedOutputStream();
          pipeIn = new PipedInputStream();
          pipeOut.connect(pipeIn);
        }
      } catch (IOException ex) {
        ex.printStackTrace();
      }
      return pipeOut;
    }

    private void close () {
      running = false;
    }

    public void run () {
      BufferedInputStream bufIn = new BufferedInputStream(pipeIn);
      while (running) {
        try {
          int size;
          while ((size = bufIn.available()) > 0) {
            byte[] buffer = new byte[size];
            bufIn.read(buffer);
            process(buffer);
          }
        } catch (IOException ex) {
          ex.printStackTrace();
        }
      }
      try {
        pipeOut.close();
        pipeIn.close();
      } catch (IOException ex) {
        ex.printStackTrace();
      }
    }

    private void process (byte[] buffer) {
      int[] samples = new int[buffer.length / 2];
      if (encoding == AudioFormat.Encoding.PCM_SIGNED && channels == 1 && frameSize == 2) {
        if (bigEndian) {
          // Handle big Endian data
          for (int ii = 0; ii < buffer.length; ii += 2) {
            samples[ii >> 1] = (buffer[ii] << 8) | (buffer[ii + 1] & 0xFF);
          }
        } else {
          // Handle little Endian data
          for (int ii = 0; ii < buffer.length; ii += 2) {
            samples[ii >> 1] = (buffer[ii + 1] << 8) | (buffer[ii] & 0xFF);
          }
        }
      } else {
        throw new IllegalArgumentException("Audio format not recognized: " + encoding);
      }
      // Compute Input Signal Level
      double level = 0;
      for (int sample : samples) {
        level += Math.abs(sample);
      }
      level = (level / (double) samples.length) / (double) 0x4000;
      levelMeter.setValue((int) (100 * level));
      // Count bit intervals by watching zero crossing
      for (int sample : samples) {
        int dx = lastSample - sample;
        boolean lastSign = lastSample > 0;
        boolean sampleSign = sample > 0;
        if (Math.abs(dx) > 10 && lastSign != sampleSign) {
          if (skipBit) {
            // skip 2nd half of '1' bit
            skipBit = false;
          } else {
            if (interval > (lastInterval + (lastInterval >> 1))) {
              // transitioned to a '0' bit
              frameWord = frameWord >> 1;
              bitValue = false;
            } else if (interval < (lastInterval - (lastInterval >> 2))) {
              // transitioned to a '1' bit
              frameWord = (frameWord >> 1) + 0x8000;
              bitValue = true;
              skipBit = true;
            } else {
              // same as last bit
              frameWord >>= 1;
              if (bitValue) {
                frameWord |= 0x8000;
                skipBit = true;
              }
            }
            // Look for end of frame sync pattern 0b1011111111111100;
            if (frameWord == SYNC) {
              if (frameIndex == 4) {
                // Time code frame received in frame[]
                decodeFrame(frame);
              }
              frameIndex = 0;
              bitCount = 0;
            } else if (++bitCount >= 16) {
              if (frameIndex < 4) {
                frame[frameIndex++] = frameWord;
              } else {
                frameIndex = 0;
              }
              bitCount = 0;
            }
          }
          lastInterval = interval;
          interval = 0;
        } else {
          interval++;
        }
        lastSample = sample;
      }
    }

    /*
     *  buf[0]  | u  u  u  u | C  d  F  F | u  u  u  u | f  f  f  f |  Frames
     *           15 14 13 12  11 10 9  8    7  6  5  4   3  2  1  0
     *
     *  buf[1]  | u  u  u  u | x  S  S  S | u  u  u  u | s  s  s  s |  Seconds
     *           31 30 29 28  27 26 25 24  23 22 21 20  19 18 17 16
     *
     *  buf[2]  | u  u  u  u | x  M  M  M | u  u  u  u | m  m  m  m |  Minutes
     *           47 46 45 44  43 42 41 40  39 38 37 36  35 34 33 32
     *
     *  buf[3]  | u  u  u  u | x  c  H  H | u  u  u  u | h  h  h  h |  Hours
     *           63 62 61 60  59 58 57 56  55 54 53 52  51 50 49 49
     *
     *  buf[4]  | 1  0  1  1 | 1  1  1  1 | 1  1  1  1 | 1  1  0  0 |  SYNC Pattern
     *           79 78 77 76  75 74 73 72  71 70 69 68  67 66 65 64
     *
     *  u = User bits, Upper case = tens, lower case = units
     *  d = drop frame flag, c = clock sync flag, C = color frame flag
     *  x = special flag bits bits (usage depends on frame rate)
     *  if 25 fps
     *    bit 59 is polarity correction bit
     *    bit 27 is BFG0 and bit 43 is BFG2
     *  else
     *    bit 27 is polarity correction bit (obsolete, or reassigned)
     *    bit 43 is BFG0 and bit 59 is BFG2
     *  if BFG0 == 1 user bits contain four 8 bit chars, else unspecified data
     */

    private void decodeFrame(int[] frame) {
      int frmUnits =  frame[0] & 0x0F;
      int frmTens  = (frame[0] >> 8) & 0x03;
      int secUnits =  frame[1] & 0x0F;
      int secTens  = (frame[1] >> 8) & 0x07;
      int minUnits =  frame[2] & 0x0F;
      int minTens  = (frame[2] >> 8) & 0x07;
      int hrsUnits =  frame[3] & 0x0F;
      int hrsTens  = (frame[3] >> 8) & 0x03;
      // Error check on data
      if (frmUnits > 9 || secUnits > 9 || minUnits > 9 || hrsUnits > 9) {
        return;
      }
      // Get flags
      boolean bit10 = (frame[0] & 0x400) != 0;       // Bit 10 drop frame (if 30 fps)
      boolean bit11 = (frame[0] & 0x800) != 0;       // Bit 11 Color frame flag (if 30, or 25 fps)
      boolean bit27 = (frame[1] & 0x800) != 0;       // Bit 27
      boolean bit43 = (frame[2] & 0x800) != 0;       // Bit 43
      boolean bit58 = (frame[3] & 0x400) != 0;       // Bit 58 also called the BFG1 flag
      boolean bit59 = (frame[3] & 0x800) != 0;       // Bit 59 phase-correction bit
      user59.setSelected(bit59);
      user58.setSelected(bit58);
      user43.setSelected(bit43);
      user27.setSelected(bit27);
      user11.setSelected(bit11);
      user10.setSelected(bit10);
      // Unpack User Bit Fields
      String userFields =
          Integer.toHexString((frame[3] >> 12) & 0x0F) +
              Integer.toHexString((frame[3] >> 4) & 0x0F) +
              Integer.toHexString((frame[2] >> 12) & 0x0F) +
              Integer.toHexString((frame[2] >> 4) & 0x0F) +
              Integer.toHexString((frame[1] >> 12) & 0x0F) +
              Integer.toHexString((frame[1] >> 4) & 0x0F) +
              Integer.toHexString((frame[0] >> 12) & 0x0F) +
              Integer.toHexString((frame[0] >> 4) & 0x0F);
      userData.setText(userFields);
      // Update Frame Rate Calculation
      if (secUnits != lastSecond) {
        lastSecond = secUnits;
        frameRate.setText((frameCount + 1) + " fps");
        frameCount = 0;
      }
      frameCount = Math.max(frameCount, frmUnits + frmTens * 10);
      // Format timecode as ASCII String
      String buf =
          Integer.toString(hrsTens) +
              Integer.toString(hrsUnits) +
              ":" +
              Integer.toString(minTens) +
              Integer.toString(minUnits) +
              ":" +
              Integer.toString(secTens) +
              Integer.toString(secUnits) +
              ":" +
              Integer.toString(frmTens) +
              Integer.toString(frmUnits);
      timeView.setText(buf);
      repaint();
    }
  }

  public void run () {
    try {
      AudioFormat format = getFormat();
      DataLine.Info dataLineInfo = new DataLine.Info(TargetDataLine.class, format);
      TargetDataLine line = (TargetDataLine)  selectedMixer.getLine(dataLineInfo);
      timecode.setFormat(format);
      line.open(format);
      line.start();
      byte buffer[] = new byte[1024];
      running = true;
      PipedOutputStream pipeOut = timecode.getPipeOut();
      while (running) {
        int count;
        while ((count = line.available()) >= buffer.length) {
          int size = line.read(buffer, 0, count > buffer.length ? buffer.length : count);
          pipeOut.write(buffer, 0, size);
        }
      }
      line.close();
      timecode.close();
    } catch (Exception ex) {
      ex.printStackTrace();
      System.exit(-2);
    }
  }

  private TimecodeReader() {
    super("Timecode Reader");
    setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    setLayout(new BorderLayout());
    add(timecode = new TimeCode(), BorderLayout.CENTER);
    add(capture = new JButton("Start"), BorderLayout.SOUTH);
    capture.setFont(capture.getFont().deriveFont(28f));
    capture.setEnabled(true);
    addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        super.windowClosing(e);
        running = false;
        try {
          // Wait for Thread to exit, or 2 seconds, whichever is shorter
          if (runThread != null) {
            runThread.join(1000);
          }
          timecode.close();
        } catch (Exception ex) {
          ex.printStackTrace();
        }
        System.exit(0);
      }
    });
    inputMenu = new JMenu("Input");
    capture.addActionListener(e -> {
      if (displaying) {
        capture.setText("Start");
        running = false;
        displaying = false;
        inputMenu.setEnabled(true);
      } else {
        (runThread = new Thread(this)).start();
        capture.setText("Stop");
        displaying = true;
        inputMenu.setEnabled(false);
      }
    });
    // Add Menu Bar
    JMenuBar menuBar = new JMenuBar();
    setJMenuBar(menuBar);
    // Add "Input" menu
    ButtonGroup group = new ButtonGroup();
    Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
    boolean hasInput = false;
    for (Mixer.Info mixerInfo : mixerInfos) {
      Mixer mixer = AudioSystem.getMixer(mixerInfo);
      Line.Info[] lineInfos = mixer.getTargetLineInfo();
      if (lineInfos.length > 0 && lineInfos[0].getLineClass().equals(TargetDataLine.class)) {
        String input = mixerInfo.getName() + " (" + mixerInfo.getVendor() + ")";
        boolean inputSelected = input.equals(prefs.get("audio.input", null));
        if (inputSelected) {
          selectedMixer = mixer;
        }
        JRadioButtonMenuItem mItem = new JRadioButtonMenuItem(input, inputSelected);
        hasInput |= inputSelected;
        inputMenu.add(mItem);
        group.add(mItem);
        mItem.addActionListener(ev -> {
          String name = ev.getActionCommand();
          selectedMixer = mixer;
          prefs.put("audio.input", name);
          capture.setEnabled(true);
        });
      }
    }
    capture.setEnabled(hasInput);
    menuBar.add(inputMenu);
    // Track window move events and save in prefs
    addComponentListener(new ComponentAdapter() {
      public void componentMoved (ComponentEvent ev)  {
        Rectangle bounds = ev.getComponent().getBounds();
        prefs.putInt("window.x", bounds.x);
        prefs.putInt("window.y", bounds.y);
      }
    });
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    setLocation(prefs.getInt("window.x", 10), prefs.getInt("window.y", 10));
    pack();
    setResizable(false);
    setVisible(true);
  }

  private AudioFormat getFormat () {
    float sampleRate = 22050;
    int sampleSizeInBits = 16;
    int channels = 1;
    // Sample rate 44,100 Hz, 16 bit, 1 channel, signed, bigEndian
    return new AudioFormat(sampleRate, sampleSizeInBits, channels, true, true);
  }

  public static void main (String args[]) throws Exception {
    new TimecodeReader();
  }
}