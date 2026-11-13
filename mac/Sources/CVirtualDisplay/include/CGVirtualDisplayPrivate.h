// Private CoreGraphics API used to create a real (but virtual) display that
// macOS treats like a physical monitor: it shows up in System Settings >
// Displays, can be arranged, and windows can be dragged onto it.
//
// The classes exist in CoreGraphics at runtime; only the declarations are
// missing from the public SDK.

#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface CGVirtualDisplayMode : NSObject
@property(readonly, nonatomic) NSUInteger width;
@property(readonly, nonatomic) NSUInteger height;
@property(readonly, nonatomic) double refreshRate;
- (instancetype)initWithWidth:(NSUInteger)width
                       height:(NSUInteger)height
                  refreshRate:(double)refreshRate;
@end

@interface CGVirtualDisplaySettings : NSObject
/// 1 = each mode is backed by 2x pixels (Retina).
@property(nonatomic) unsigned int hiDPI;
@property(retain, nonatomic) NSArray<CGVirtualDisplayMode *> *modes;
@end

@interface CGVirtualDisplayDescriptor : NSObject
@property(retain, nonatomic, nullable) dispatch_queue_t queue;
@property(retain, nonatomic) NSString *name;
@property(nonatomic) unsigned int maxPixelsWide;
@property(nonatomic) unsigned int maxPixelsHigh;
@property(nonatomic) CGSize sizeInMillimeters;
@property(nonatomic) unsigned int vendorID;
@property(nonatomic) unsigned int productID;
@property(nonatomic) unsigned int serialNum;
- (void)setDispatchQueue:(dispatch_queue_t)queue NS_SWIFT_NAME(setDispatchQueue(_:));
@end

@interface CGVirtualDisplay : NSObject
@property(readonly, nonatomic) CGDirectDisplayID displayID;
- (nullable instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor *)descriptor;
- (BOOL)applySettings:(CGVirtualDisplaySettings *)settings NS_SWIFT_NAME(applySettings(_:));
@end

NS_ASSUME_NONNULL_END
