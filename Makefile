.PHONY: bootstrap-install inline test integration-test install deploy clean

# Note that bootstrapping is a two-step process: given that mranderson depends on
# itself as a plugin, it first needs to be installed without the plugin, so that
# the self dependency can be resolved.
bootstrap-install:
	lein clean
	lein with-profile -user,-dev install
	lein with-profile -user,-dev,+mranderson-plugin install

.inline: bootstrap-install
	rm target/*.jar
	rm pom.xml
	lein with-profile -user,-dev,+mranderson-plugin inline-deps :skip-javaclass-repackage true
	touch .inline

inline: .inline

test:
	lein test

integration-test:
	scripts/integration_test.sh

# Install mranderson, with its own dependencies inlined, to the local maven
# repository so that other projects can depend on it.
install: .inline
	lein with-profile -user,-dev,+mranderson-profile install

deploy: .inline
	lein with-profile -user,-dev,+mranderson-profile deploy clojars

clean:
	lein clean
	rm -rf .inline
