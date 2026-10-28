//! Tokenizer-free replay of the exact JsonQuote AST used by pinned LLGuidance.
use llguidance::derivre::{JsonQuoteOptions, RegexAst, RegexBuilder};
use serde_json::{json, Value};
use std::io::{self, Read, Write};

fn probe(input: Value) -> Result<Value, String> {
    let cases = input["cases"].as_array().ok_or("missing cases")?;
    let prefixes = input["prefixes"].as_array().ok_or("missing prefixes")?;
    if cases.len() > 64 || prefixes.len() > 16 {
        return Err("probe count bound".into());
    }
    let mut builder = RegexBuilder::new();
    // Copied expression/options, not patched dependency behavior. See pinned
    // LLGuidance compiler.rs gen_json_string/json_quote for the exact branch.
    let expr = builder
        .mk(&RegexAst::JsonQuote(
            Box::new(RegexAst::Regex("(?s:.{1,96})".to_string())),
            JsonQuoteOptions {
                allowed_escapes: "nrbtf\\\"u".to_string(),
                raw_mode: false,
            },
        ))
        .map_err(|e| e.to_string())?;
    let mut regex = builder
        .to_regex_limited(expr, 1_000_000)
        .map_err(|e| e.to_string())?;
    let mut outputs = Vec::new();
    for case in cases {
        let text = case["jsonText"].as_str().ok_or("missing JSON text")?;
        if text.len() > 8192 {
            return Err("text bound".into());
        }
        let mut state = regex.initial_state();
        let mut first_dead = None;
        for (offset, byte) in text.bytes().enumerate() {
            state = regex.transition(state, byte);
            if state.is_dead() && first_dead.is_none() {
                first_dead = Some(offset);
            }
        }
        outputs.push(json!({
            "id":case["id"], "accepted":regex.is_accepting(state),
            "firstDeadByte":first_dead,
        }));
    }
    let mut states = Vec::new();
    for prefix in prefixes {
        let text = prefix.as_str().ok_or("bad prefix")?;
        if text.len() > 1024 {
            return Err("prefix bound".into());
        }
        let start = regex.initial_state();
        let state = regex.transition_bytes(start, text.as_bytes());
        let allowed: Vec<u8> = (0..=255)
            .filter(|b| !regex.transition(state, *b).is_dead())
            .collect();
        states.push(json!({"prefix":text,"allowedNextBytes":allowed,"isDead":state.is_dead()}));
    }
    Ok(json!({"scope":"tokenizer-free-pinned-JsonQuote","cases":outputs,"prefixes":states}))
}
fn main() {
    let result = (|| {
        let mut input = String::new();
        io::stdin()
            .take(1_048_577)
            .read_to_string(&mut input)
            .map_err(|e| e.to_string())?;
        if input.len() > 1_048_576 {
            return Err("input bound".to_string());
        }
        probe(serde_json::from_str(&input).map_err(|e| e.to_string())?)
    })();
    match result {
        Ok(value) => {
            let mut out = io::stdout().lock();
            if serde_json::to_writer(&mut out, &value).is_err() || out.write_all(b"\n").is_err() {
                std::process::exit(3);
            }
        }
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(2);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn accepts(text: &str) -> bool {
        let result = probe(json!({"cases":[{"id":"probe","jsonText":text}],"prefixes":[]}))
            .expect("bounded fixture");
        result["cases"][0]["accepted"].as_bool().expect("boolean")
    }

    #[test]
    fn equivalent_literal_and_escape_have_different_grammar_acceptance() {
        assert!(accepts("\"青\""));
        assert!(!accepts("\"\\u9752\""));
        assert!(accepts("\"🙂\""));
        assert!(!accepts("\"\\ud83d\\ude42\""));
    }

    #[test]
    fn ascii_escape_domain_matches_documented_control_restriction() {
        for code in 0..128 {
            let text = format!("\"\\u{code:04x}\"");
            assert_eq!(accepts(&text), code < 32 || code == 127, "{text}");
        }
    }

    #[test]
    fn prefix_rejects_nonzero_first_hex_digit_without_a_tokenizer() {
        let result = probe(json!({"cases":[],"prefixes":["\"\\u"]})).expect("prefix");
        assert_eq!(result["prefixes"][0]["allowedNextBytes"], json!([48]));
    }

    #[test]
    fn invalid_and_oversized_inputs_are_rejected() {
        assert!(probe(json!({"cases":[],"prefixes":[null]})).is_err());
        assert!(probe(json!({"cases":[{"jsonText":"x".repeat(8193)}],"prefixes":[]})).is_err());
        assert!(probe(json!({"cases":vec![json!({});65],"prefixes":[]})).is_err());
        assert!(probe(json!({"cases":[],"prefixes":["x".repeat(1025)]})).is_err());
    }
}
