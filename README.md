# BluetoothScanner
This is Bluetooth classic and BLE scanner app written in Java

# To test with Classic:
Make sure you can see
```
Service Name: Serial Port
Service RecHandle: <handle>
Service Class ID List:
  "Serial Port" (0x1101)
Protocol Descriptor List:
  "L2CAP" (0x0100)
  "RFCOMM" (0x0003)
    Channel: 1
```
after running `sdptool browse local`
and :
```
UP RUNNING PSCAN ISCAN
```
after running `hciconfig hci0`.

Run: `sudo rfcomm listen /dev/rfcomm0 1`

Now the app can pair, connect to channel 1, and send data, which is shown in `/dev/rfcomm0`
