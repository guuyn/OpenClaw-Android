# Notification Classification Model

Place a TensorFlow Lite model file here as `notification_classifier.tflite`.

## Model Requirements

- Format: BertNLClassifier compatible TFLite model
- Input: text string (notification content)
- Output: 4 categories with confidence scores:
  - `urgent` - Emergency notifications (calls, mentions, alerts)
  - `important` - Work-related notifications (meetings, approvals)
  - `normal` - General notifications (news, social updates)
  - `noise` - Spam/promotional content

## Training Data

The model should be trained on labeled notification samples with features:
- Application package name
- Notification title
- Notification text body

## Without a Model

The classifier gracefully falls back to rule-based classification when no model file is present.
No runtime errors will occur.
