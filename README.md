# TimecodeReader

**`TimecodeReader`** can read an audio input signal using either a microphone, or line-level input and decode SMPTE/EBU Timecode.  Use the "**Input**" menu to select the source of the signal and then press "**Start**" to begin realtime timecode decoding and display.  Then, adjust the input level to keep the "**Input Level**" bar graph within a reasonable range. If you're using a microphone input, you may need to use an audio attenuator cable to reduce the signal level and avoid overdriving the input.  The "**Estimated Frame Rate**" field tries to estimate the frame rate by watching for the maximum frame number between sucessive seconds and displaying the value + 1.

For your convenience, an executable .jar file for **`TimecodeReader`** can be [downloaded here](https://github.com/wholder/TimecodeReader/tree/master/out/artifacts/TimecodeReader_jar).  On most systems you can run the .jar file by double clicking it to launch.  Note: on OSX, you may need to right click and select "Open" the first time you run the program.

<p align="center"><img src="https://github.com/wholder/TimecodeReader/blob/master/images/TimecodeReader%20Screenshot.png"></p>
