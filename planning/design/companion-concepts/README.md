# Companion UI Concepts

Four generated concept boards, each containing two Android screens. These explore the user's offline feature proposals while preserving the mascot and continuous conversation as the main experience. They are visual mockups, not screenshots of implemented software.

Update, 26 September 2026: the user approved this direction through the four attached boards. Use [design/approved](../approved/README.md) as the canonical visual references and the [implementation plan](../../IMPLEMENTATION_PLAN.md) for behavior. This folder retains the generation history.

| Board | Left screen | Right screen |
| --- | --- | --- |
| [Mascot and voice-to-action](01-mascot-voice.png) | Mascot, local listening waveform, and live transcript | Same conversation with reminder and tagged micro-note confirmation |
| [Receipt scanning and follow-up](02-receipt-conversation.png) | On-device receipt capture | Extracted expense preview and a follow-up grounded in the receipt |
| [Local memory and source inspection](03-local-memory.png) | Full-app conversation grounded in local notes and personal context | Inspect the referenced voice note, transcript, and linked memories |
| [Text polishing and quick actions](04-writing-and-timer.png) | Refine explicitly shared text over multiple turns | Start and control a workout timer through the same assistant drawer |

## Design Direction

The mint robot with a coral antenna is an exploratory mascot, now used consistently across the boards. White surfaces, graphite text, green action controls, and a small shield with "On device" communicate the current processing state. Listening has a waveform; normal interaction stays conversational. The expand icon connects the drawer to the full app.

The full app opens into a conversation and offers Memory and Sources as secondary destinations. Sources remain inspectable, and follow-up questions retain the current document or topic. Text polishing ends with copy/share controls. The action preview remains compact within the conversation.

All people, notes, receipts, amounts, and conversations in these concepts are fictional example content. The mascot direction is now approved; the product name remains a placeholder.

## Implementation Meaning

"On device" describes the depicted local processing. The concepts make no measured latency or absolute privacy claims. Calendar insertion assumes an available local calendar and authorized access; timer controls illustrate an app-owned timer. Other system controls must reflect the actual capabilities and permissions of the target platform.

Camera, microphone, document, and calendar access use their corresponding consent flows. Text arrives through explicit sharing or paste. These are feature concepts; they do not demonstrate working OCR, speech recognition, model inference, retrieval, encryption, or system integration.

A future online fallback should show the proposed lookup and ask before connecting. These boards concentrate on the offline experience requested here; they do not add automatic web requests or a cloud model fallback.

## Generation

Created with the built-in image generation tool. The first board is the visual reference for the other three. Exact prompts and reference information are retained in [PROMPTS.md](PROMPTS.md). Originals remain in the tool's generated-image directory; selected deliverables are copied here.
