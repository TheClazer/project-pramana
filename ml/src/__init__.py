"""Pramāṇa ML pipeline — Engineer A's track.

Modules:
    data.*        dataset loaders (FF++, Celeb-DF, IIIT-CFW, stand-in)
    models.*      backbones + binary head
    rppg.*        Python rPPG prototype (contract for the Kotlin port)
    workbench.*   Qualcomm AI Hub Workbench compile/quantize/profile
    train         training loop
    eval          metrics
    reference_inference  THE preprocessing contract — Android must match
    gradcam       Stretch 1 heatmap generator
"""
__version__ = "0.1.0"
