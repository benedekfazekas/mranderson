{:config ^:leaky {:omit-source true
                  :source-paths ["target/srcdeps"]
                  :filespecs [{:type :paths :paths ["target/srcdeps"]}]
                  :auto-clean false
                  :srcdeps-project-hacks true
                  :jar-exclusions [#"(?i)^META-INF/.*"]}}
