# Add project specific ProGuard rules here.

# The Shizuku user service is instantiated by Shizuku in a separate process via
# reflection, so its class name and constructors must be preserved.
-keep class com.bennod.pkmninstaller.shizuku.UserService { *; }
-keep class com.bennod.pkmninstaller.shizuku.IUserService { *; }
