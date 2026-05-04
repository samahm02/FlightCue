# FlightCue

FlightCue is an Android app that detects aircraft takeoff and landing
automatically, using only the phone's accelerometer and barometer. Everything
runs on-device: no internet, no cloud.

## What it does

The app runs a foreground service that continuously reads sensor data, extracts
features, and feeds them into two GRU models (one for takeoff, one for landing)
via ONNX Runtime. When the model is confident enough for enough consecutive
windows, it fires an event. You can also mark takeoff and landing manually if
needed.

Detected flights are saved locally as JSONL logs and can be exported as a ZIP
file directly from the app.

## How it's built

The project uses MVVM with a clean domain layer that has no Android
dependencies: the feature extraction, detector state machine, and windowing
logic are all pure Kotlin and fully testable on the JVM.

The feature pipeline is a close port of the Python preprocessing script used
during training. An end-to-end parity test (`FeatureParityTest`) verifies that
all 154 features match the Python reference within 1e-5 tolerance, with no
device required.

## ML pipeline

The models were trained using a separate pipeline:
[FlightCue-ML](https://github.com/samahm02/FlightCue-ML)

## Requirements

Android API 28 or higher, with both accelerometer and barometer sensors
present.

## Thesis

Developed as part of a master's thesis at the University of Oslo, covering
dataset collection, model training, and evaluation against a prior rule-based
system.
