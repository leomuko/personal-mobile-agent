#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>
#include "mediapipe/tasks/cc/text/custom_ops/sentencepiece/optimized_encoder.h"

// Host-only oracle. The tokenizer implementation is unmodified, hash-pinned upstream code.
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  std::ifstream input(argv[1], std::ios::binary);
  std::vector<char> config((std::istreambuf_iterator<char>(input)), {});
  if (config.size() != 384344) return 3;
  flatbuffers::Verifier verifier(reinterpret_cast<const uint8_t*>(config.data()), config.size());
  namespace sp = mediapipe::tflite_operations::sentencepiece;
  if (!sp::VerifyEncoderConfigBuffer(verifier)) return 4;
  std::string line;
  while (std::getline(std::cin, line)) {
    if (line.size() > 32768 || line.size() % 2 != 0) return 5;
    std::string text;
    for (size_t at = 0; at < line.size(); at += 2) {
      const auto pair = line.substr(at, 2);
      if (pair.find_first_not_of("0123456789abcdef") != std::string::npos) return 6;
      text.push_back(static_cast<char>(std::stoi(pair, nullptr, 16)));
    }
    const auto result = sp::EncodeString(text, config.data(), true, true, false);
    if (result.type != sp::EncoderResultType::SUCCESS) return 7;
    for (size_t at = 0; at < result.codes.size(); ++at) {
      if (at) std::cout << ',';
      std::cout << result.codes[at];
    }
    std::cout << '\n';
  }
}
