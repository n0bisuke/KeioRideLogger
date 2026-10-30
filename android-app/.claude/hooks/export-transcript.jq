def jst: sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601 + 32400 | strftime("%Y-%m-%d %H:%M");
def q: "[\"']";
def redact:
  gsub("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"; "[REDACTED:PRIVATE_KEY]")
  | gsub("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"; "[REDACTED:EMAIL]")
  | gsub("\\b(sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,}|xox[abpr]-[A-Za-z0-9-]{10,})"; "[REDACTED:TOKEN]")
  | gsub("(?i)(?<k>bearer\\s+)[A-Za-z0-9._~+/=-]{16,}"; "\(.k)[REDACTED:TOKEN]")
  | gsub("(?i)(?<k>(password|passwd|secret|token|api[_-]?key)" + q + "?\\s*[:=]\\s*)" + q + "?[A-Za-z0-9._~+/=!@#$%^&*-]{4,}"; "\(.k)[REDACTED]")
  | gsub("-?\\b[0-9]{1,3}\\.[0-9]{4,}\\s*,\\s*-?[0-9]{1,3}\\.[0-9]{4,}\\b"; "[REDACTED:LATLON]");
def clean: gsub("(?s)<system-reminder>.*?</system-reminder>"; "")
         | gsub("<pasted_content[^>]*>"; "\n```text\n") | gsub("</pasted_content[^>]*>"; "\n```\n")
         | gsub("^\\s+|\\s+$"; "");
[ .[]
  | select(.type == "user" or .type == "assistant" or (.type == "attachment" and .attachment.type == "queued_command"))
  | select(.isMeta != true)
  | .timestamp as $ts
  | if .type == "attachment" then
      (.attachment.prompt // "" | clean) | select(length > 0)
      | {role: "user", ts: $ts, parts: ["（作業中に送信）\n\n" + .]}
    elif .type == "user" then
      (if (.message.content | type) == "string" then .message.content
       else [.message.content[] | select(.type == "text") | .text] | join("\n") end) | clean
      | select(length > 0)
      | {role: "user", ts: $ts, parts: [.]}
    else
      [.message.content[]
        | if .type == "text" then (.text | clean)
          elif .type == "tool_use" then
            "> 🔧 \(.name): \(.input.description // .input.file_path // .input.skill // .input.command // "" | tostring | split("\n")[0])"
          else empty end
        | select(length > 0)]
      | select(length > 0)
      | {role: "assistant", ts: $ts, parts: .}
    end ]
| reduce .[] as $m ([];
    if length > 0 and .[-1].role == "assistant" and $m.role == "assistant"
    then .[-1].parts += $m.parts else . + [$m] end)
| .[]
| if .role == "user" then "## 👤 ユーザー (\(.ts | jst))\n\n\(.parts | join("\n\n"))\n"
  else "### 🤖 Claude (\(.ts | jst))\n\n\(.parts | join("\n\n"))\n" end
| redact
