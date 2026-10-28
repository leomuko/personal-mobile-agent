//! Standalone synthetic mask probe. No model, sampler, JNI or application state.
use llguidance::ffi::*;
use serde_json::{json, Value};
use std::{
    ffi::{c_char, c_void, CStr, CString},
    io::{self, BufRead, Read, Write},
    panic::{catch_unwind, AssertUnwindSafe},
    ptr,
    sync::Mutex,
};

static TOKENIZER_BROKER: Mutex<()> = Mutex::new(());

fn read_line() -> Result<Value, String> {
    let mut line = String::new();
    let n = io::stdin()
        .lock()
        .take(1_048_577)
        .read_line(&mut line)
        .map_err(|e| e.to_string())?;
    if n == 0 || n > 1_048_576 {
        return Err("missing/oversized protocol line".into());
    }
    serde_json::from_str(&line).map_err(|e| e.to_string())
}
fn write_frame(writer: &mut impl Write, v: &Value) -> Result<(), String> {
    serde_json::to_writer(&mut *writer, v).map_err(|e| e.to_string())?;
    writer.write_all(b"\n").map_err(|e| e.to_string())?;
    writer.flush().map_err(|e| e.to_string())
}
fn emit(v: Value) -> Result<(), String> {
    write_frame(&mut io::stdout().lock(), &v)
}

fn validate_callback_buffers(
    bytes: *const u8,
    len: usize,
    out: *mut u32,
    cap: usize,
) -> Result<(), String> {
    if len > 1_048_576 || (len > 0 && bytes.is_null()) {
        return Err("invalid callback input".into());
    }
    if cap > isize::MAX as usize / std::mem::size_of::<u32>()
        || (cap > 0 && (out.is_null() || (out as usize) % std::mem::align_of::<u32>() != 0))
    {
        return Err("invalid callback output".into());
    }
    Ok(())
}
// The broker uses the exact pinned SentencePiece model; this callback never guesses.
// Any broker failure kills this diagnostic process; it cannot publish a success report.
extern "C" fn tokenize(
    _: *const c_void,
    bytes: *const u8,
    len: usize,
    out: *mut u32,
    cap: usize,
) -> usize {
    // All panics and operational failures are contained before returning through C.
    // Process exit is deliberate: standalone diagnostics cannot credit a partial reply.
    match catch_unwind(AssertUnwindSafe(|| tokenize_inner(bytes, len, out, cap))) {
        Ok(Ok(count)) => count,
        _ => std::process::exit(3),
    }
}
fn tokenize_inner(
    bytes: *const u8,
    len: usize,
    out: *mut u32,
    cap: usize,
) -> Result<usize, String> {
    let _guard = TOKENIZER_BROKER.lock().map_err(|e| e.to_string())?;
    validate_callback_buffers(bytes, len, out, cap)?;
    // SAFETY: the pinned caller owns a readable len-byte allocation until callback
    // return. Checks above cover null/length, not liveness/provenance, which are
    // the caller's documented obligation. No borrow survives this callback.
    let raw = if len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(bytes, len) }
    };
    emit(json!({"event":"tokenize", "bytes":raw}))?;
    let response = read_line()?;
    let ids = response["tokens"]
        .as_array()
        .ok_or("missing callback tokens")?;
    for (i, v) in ids.iter().enumerate() {
        let token = v
            .as_u64()
            .filter(|n| *n < 262144)
            .ok_or("invalid callback token")? as u32;
        // SAFETY: pinned caller supplies a disjoint writable cap-element allocation;
        // pointer layout checked above and i<cap bounds every write. Never retained.
        if i < cap {
            unsafe {
                *out.add(i) = token;
            }
        }
    }
    Ok(ids.len())
}
fn check(c: *mut LlgConstraint) -> Result<(), String> {
    if c.is_null() || (c as usize) % std::mem::align_of::<LlgConstraint>() != 0 {
        return Err("invalid constraint handle".into());
    }
    // SAFETY: c is owned by run(), live until the matching llg_free_constraint.
    let e = unsafe { llg_get_error(&*c) };
    if e.is_null() {
        Ok(())
    } else {
        Err(unsafe { CStr::from_ptr(e) }.to_string_lossy().into())
    }
}
fn sample(c: *mut LlgConstraint, watch: &[u32], eos: u32) -> Result<Value, String> {
    let mut mask = LlgMaskResult {
        sample_mask: ptr::null(),
        temperature: 0.0,
        is_stop: false,
    };
    if llg_compute_mask(unsafe { &mut *c }, &mut mask) != 0 {
        check(c)?;
        return Err("mask failed".into());
    }
    if !mask.sample_mask.is_null() && (mask.sample_mask as usize) % std::mem::align_of::<u32>() != 0
    {
        return Err("misaligned mask".into());
    }
    // The pinned producer initializes bool fields with Rust booleans and gives
    // vocab_size/32 live words until the next llg call. Copy before any such call.
    let allowed = |id: u32| -> bool {
        if mask.sample_mask.is_null() {
            !mask.is_stop || id == eos
        } else {
            unsafe { *mask.sample_mask.add(id as usize / 32) & (1u32 << (id % 32)) != 0 }
        }
    };
    let bits: Vec<_> = watch
        .iter()
        .map(|id| json!({"id":id,"allowed":allowed(*id)}))
        .collect();
    let count = (0..262144).filter(|id| allowed(*id)).count();
    let words: Vec<u32> = (0..8192)
        .map(|i| {
            (0..32).fold(0u32, |word, bit| {
                if allowed(i * 32 + bit) {
                    word | (1u32 << bit)
                } else {
                    word
                }
            })
        })
        .collect();
    Ok(
        json!({"watch":bits,"allowedCount":count,"isStop":mask.is_stop,"nullMask":mask.sample_mask.is_null(),"maskWords":words}),
    )
}
fn commit(c: *mut LlgConstraint, id: u32) -> Result<(), String> {
    let mut result = LlgCommitResult {
        tokens: ptr::null(),
        n_tokens: 0,
        is_stop: false,
    };
    if llg_commit_token(unsafe { &mut *c }, id, &mut result) != 0 {
        check(c)?;
        return Err("commit failed".into());
    }
    // Matches LiteRT's ff_tokens_ok=false, backtrack_ok=false setup.
    if result.n_tokens != 1
        || result.tokens.is_null()
        || (result.tokens as usize) % std::mem::align_of::<u32>() != 0
        || unsafe { *result.tokens } != id
    {
        return Err("unexpected fast-forward/commit result".into());
    }
    Ok(())
}
fn run() -> Result<(), String> {
    let cfg = read_line()?;
    let eos = cfg["eos"]
        .as_u64()
        .filter(|v| *v < 262144)
        .ok_or("bad EOS")? as u32;
    let file = cfg["vocabulary"].as_str().ok_or("missing vocabulary")?;
    // Bound allocation before reading; the extra byte detects growth/racing size.
    let input = std::fs::File::open(file).map_err(|e| e.to_string())?;
    let mut bytes = Vec::new();
    input
        .take(16 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() < 262144 * 4 || bytes.len() > 16 * 1024 * 1024 {
        return Err("bad vocabulary size".into());
    }
    let lens: Vec<u32> = bytes[..262144 * 4]
        .chunks_exact(4)
        .map(|b| u32::from_le_bytes(b.try_into().unwrap()))
        .collect();
    let total: usize = lens.iter().map(|v| *v as usize).sum();
    if bytes.len() != 262144 * 4 + total {
        return Err("vocabulary framing mismatch".into());
    }
    let init = LlgTokenizerInit {
        vocab_size: 262144,
        tok_eos: eos,
        token_lens: lens.as_ptr(),
        token_bytes: bytes[262144 * 4..].as_ptr(),
        tokenizer_json: ptr::null(),
        tokenize_assumes_string: false,
        tokenize_fn: Some(tokenize),
        use_approximate_greedy_tokenize_fn: false,
        tokenize_user_data: ptr::null(),
        slices: ptr::null(),
    };
    let mut error = [0 as c_char; 1024];
    // SAFETY: lens/bytes and static callback outlive tok; error is a writable fixed buffer.
    let tok = unsafe { llg_new_tokenizer(&init, error.as_mut_ptr(), error.len()) };
    if tok.is_null() {
        return Err(unsafe { CStr::from_ptr(error.as_ptr()) }
            .to_string_lossy()
            .into());
    }
    let result = (|| {
        // Every field accepts zero/null; set_defaults then initializes the actual configuration.
        let mut ci: LlgConstraintInit = unsafe { std::mem::zeroed() };
        llg_constraint_init_set_defaults(&mut ci, tok);
        let schema = CString::new(cfg["schema"].as_str().ok_or("missing exact schema")?)
            .map_err(|e| e.to_string())?;
        let c = llg_new_constraint_json(&ci, schema.as_ptr());
        if c.is_null() {
            return Err("null constraint handle".into());
        }
        let inner = (|| {
            check(c)?;
            let ids = cfg["prefix"].as_array().ok_or("missing prefix IDs")?;
            if ids.len() > 4096 {
                return Err("prefix too long".into());
            }
            let watch: Vec<u32> = cfg["watch"]
                .as_array()
                .ok_or("missing watched IDs")?
                .iter()
                .map(|v| {
                    v.as_u64()
                        .filter(|n| *n < 262144)
                        .map(|n| n as u32)
                        .ok_or("bad watch ID".into())
                })
                .collect::<Result<_, String>>()?;
            if watch.len() > 32 {
                return Err("too many watched IDs".into());
            }
            for (position, id) in ids.iter().enumerate() {
                let id = id.as_u64().filter(|n| *n < 262144).ok_or("bad prefix ID")? as u32;
                let m = sample(c, &[id], eos)?;
                if m["watch"][0]["allowed"] != true {
                    emit(
                        json!({"event":"result","status":"prefix-rejected","position":position,"token":id,"mask":m}),
                    )?;
                    return Ok(());
                }
                commit(c, id)?;
            }
            let counts = cfg["newlineCounts"]
                .as_array()
                .ok_or("missing newline checkpoints")?;
            let mut done = 0u64;
            let mut states = vec![];
            for value in counts {
                let count = value
                    .as_u64()
                    .filter(|n| *n <= 512)
                    .ok_or("bad checkpoint")?;
                if count < done {
                    return Err("nonmonotonic checkpoints".into());
                }
                while done < count {
                    let m = sample(c, &[107], eos)?;
                    if m["watch"][0]["allowed"] != true {
                        emit(
                            json!({"event":"result","status":"newline-rejected","after":done,"mask":m}),
                        )?;
                        return Ok(());
                    }
                    commit(c, 107)?;
                    done += 1;
                }
                states.push(json!({"newlines":count,"mask":sample(c,&watch,eos)?}));
            }
            emit(
                json!({"event":"result","status":"replayed","prefixTokens":ids.len(),"states":states}),
            )?;
            Ok(())
        })();
        unsafe { llg_free_constraint(c) };
        inner
    })();
    unsafe { llg_free_tokenizer(tok) };
    result
}
fn main() {
    if let Err(error) = run() {
        if emit(json!({"event":"error","message":error})).is_err() {
            std::process::exit(3);
        }
        std::process::exit(2);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[cfg(target_pointer_width = "64")]
    fn pinned_c_header_layout_matches() {
        use std::mem::{align_of, offset_of, size_of};
        // Independently compiled from the unchanged pinned llguidance.h.
        assert_eq!(size_of::<LlgMaskResult>(), 16);
        assert_eq!(align_of::<LlgMaskResult>(), 8);
        assert_eq!(offset_of!(LlgMaskResult, is_stop), 12);
        assert_eq!(size_of::<LlgCommitResult>(), 16);
        assert_eq!(offset_of!(LlgCommitResult, n_tokens), 8);
        assert_eq!(size_of::<LlgConstraintInit>(), 80);
        assert_eq!(size_of::<LlgTokenizerInit>(), 72);
    }
    #[test]
    fn accepts_empty_size_queries() {
        assert!(validate_callback_buffers(ptr::null(), 0, ptr::null_mut(), 0).is_ok());
    }
    #[test]
    fn rejects_null_nonempty_input() {
        assert!(validate_callback_buffers(ptr::null(), 1, ptr::null_mut(), 0).is_err());
    }
    #[test]
    fn rejects_null_writable_output() {
        let b = [0u8];
        assert!(validate_callback_buffers(b.as_ptr(), 1, ptr::null_mut(), 1).is_err());
    }
    #[test]
    fn rejects_misaligned_output() {
        let mut b = [0u32; 2];
        let p = b.as_mut_ptr().cast::<u8>().wrapping_add(1).cast::<u32>();
        assert!(validate_callback_buffers(ptr::null(), 0, p, 1).is_err());
    }
    #[test]
    fn accepts_live_aligned_buffers() {
        let b = [0u8; 2];
        let mut out = [0u32; 2];
        assert!(validate_callback_buffers(b.as_ptr(), 2, out.as_mut_ptr(), 2).is_ok());
    }
    #[test]
    fn broken_pipe_is_an_error_not_a_panic() {
        struct Broken;
        impl Write for Broken {
            fn write(&mut self, _: &[u8]) -> io::Result<usize> {
                Err(io::ErrorKind::BrokenPipe.into())
            }
            fn flush(&mut self) -> io::Result<()> {
                Ok(())
            }
        }
        assert!(write_frame(&mut Broken, &json!({"test":true})).is_err());
    }
}
