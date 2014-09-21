{:config ^:leaky {:omit-source true
                  :source-paths ["target/srcdeps"]
                  :filespecs [{:type :paths :paths ["target/srcdeps"]}]
                  :auto-clean false
                  :deps-aot true
                  :jar-exclusions [#"(?i)^META-INF/.*"]}}
