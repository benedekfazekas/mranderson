.PHONY: install inline test integration-test deploy clean

# Note that `install` is a two-step process: given that mranderson depends on itself as a plugin,
# it first needs to be installed without the plugin, for bootstrapping this self dependency.
install:
	lein clean
	lein with-profile -user,-dev install
	lein with-profile -user,-dev,+mranderson-plugin install

.inline: install
	rm target/*.jar
	rm pom.xml
	lein with-profile -user,-dev,+mranderson-plugin inline-deps :skip-javaclass-repackage true
	touch .inline

inline: .inline

test:
	lein test

integration-test:
	.circleci/integration_test.sh

deploy: .inline
	lein with-profile -user,-dev,+mranderson-profile deploy clojars

clean:
	lein clean
	rm -rf .inline
