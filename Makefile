# Note that `install` is a two-step process: given that mranderson depends on itself as a plugin,
# it first needs to be installed without the plugin, for bootstrapping this self dependency.
install:
	lein clean
	lein with-profile -user,-dev install
	lein with-profile -user,-dev,+mranderson-plugin install
