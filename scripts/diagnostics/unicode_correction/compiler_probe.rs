//! Byte-alphabet checks of the actual schema compiler, with no SentencePiece or model.
use llguidance::{api::GrammarInit, toktrie::ApproximateTokEnv, ParserFactory};
use serde_json::{json, Value};
use std::io::{self, BufRead, Read, Write};
use std::time::Instant;

fn probe(input: &Value) -> Result<Value, String> {
    let cases = input["cases"].as_array().ok_or("missing cases")?;
    if cases.len() > 64 {
        return Err("case count bound".into());
    }
    let env = ApproximateTokEnv::single_byte_env();
    let mut factory = ParserFactory::new_simple(&env).map_err(|e| e.to_string())?;
    factory.quiet();
    let mut results = Vec::new();
    for case in cases {
        let id = case["id"].as_str().ok_or("missing case id")?;
        let text = case["jsonText"].as_str().ok_or("missing JSON spelling")?;
        let schema = case.get("schema").ok_or("missing schema")?;
        if id.len() > 160 || text.len() > 8192 || schema.to_string().len() > 8192 {
            return Err("case size bound".into());
        }
        let started = Instant::now();
        let parsed = factory.create_parser_from_init_default(GrammarInit::Serialized(
            llguidance::api::TopLevelGrammar::from_json_schema(schema.clone()),
        ));
        let compile_us = started.elapsed().as_micros();
        match parsed {
            Err(error) => results.push(json!({"id":id,"status":"compile-error",
                "error":error.to_string(),"compileUs":compile_us})),
            Ok(mut parser) => {
                parser.start_without_prompt();
                let started = Instant::now();
                let mut rejected_at = None;
                for (position, byte) in text.bytes().enumerate() {
                    // Stock byte environment token n has exactly byte n. No tokenizer.encode call.
                    if parser.parser.apply_token(&[byte], u32::from(byte)).is_err() {
                        rejected_at = Some(position);
                        break;
                    }
                }
                let accepted = rejected_at.is_none() && parser.parser.is_accepting();
                results.push(json!({"id":id,"status":"checked","accepted":accepted,
                    "rejectedAtByte":rejected_at,"compileUs":compile_us,
                    "replayUs":started.elapsed().as_micros()}));
            }
        }
    }
    Ok(
        json!({"event":"result","scope":"actual-compiler-byte-alphabet",
        "sentencePieceUsed":false,"cases":results}),
    )
}

fn main() {
    let result = (|| {
        let mut data = Vec::new();
        io::stdin()
            .lock()
            .take(1_048_577)
            .read_until(b'\n', &mut data)
            .map_err(|e| e.to_string())?;
        if data.len() > 1_048_576 {
            return Err("input byte bound".to_string());
        }
        let value: Value = serde_json::from_slice(&data).map_err(|e| e.to_string())?;
        probe(&value)
    })();
    let response = match result {
        Ok(value) => value,
        Err(error) => json!({"event":"error","message":error}),
    };
    let mut out = io::stdout().lock();
    if serde_json::to_writer(&mut out, &response).is_err() || out.write_all(b"\n").is_err() {
        std::process::exit(3);
    }
    if response["event"] == "error" {
        std::process::exit(2);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn accepts(text: &str, bound: usize) -> bool {
        let result = probe(&json!({"cases":[{"id":"test","jsonText":text,"schema":{
            "type":"string","minLength":1,"maxLength":bound,
            "x-guidance":{"json_allow_general_unicode_escapes":true}}}]}))
        .expect("probe");
        assert_eq!(result["cases"][0]["status"], "checked");
        result["cases"][0]["accepted"]
            .as_bool()
            .expect("acceptance")
    }
    #[test]
    fn literal_and_escaped_scalar_are_equivalent() {
        assert!(accepts("\"青\"", 1));
        assert!(accepts("\"\\u9752\"", 1));
    }
    #[test]
    fn paired_surrogate_counts_once() {
        assert!(accepts("\"\\ud83d\\ude42\"", 1));
        assert!(!accepts("\"\\ud83d\\ude42A\"", 1));
    }
    #[test]
    fn lone_surrogates_never_complete() {
        assert!(!accepts("\"\\ud800\"", 1));
        assert!(!accepts("\"\\udc00\"", 1));
    }
    #[test]
    fn literal_and_escaped_del_are_equivalent() {
        assert!(accepts("\"\u{7f}\"", 1));
        assert!(accepts("\"\\u007f\"", 1));
    }
    #[test]
    fn malformed_escapes_do_not_complete() {
        assert!(!accepts("\"\\u97G2\"", 1));
        assert!(!accepts("\"\\u975\"", 1));
    }
    #[test]
    fn malformed_and_oversized_case_fails_explicitly() {
        assert!(probe(&json!({})).is_err());
        assert!(probe(&json!({"cases":vec![json!({});65]})).is_err());
    }
}
