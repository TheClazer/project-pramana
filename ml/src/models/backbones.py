"""Bible §6 backbones — MobileNet-V3-Small (primary) and EfficientNet-B0
(secondary), both in qai_hub_models with confirmed HTP support.

Train both during prep; pick the one with better INT8 accuracy after
quantization through AI Hub Workbench.

Binary head: 2 logits → softmax. The Kotlin side (RealDetectionEngine)
expects [real_logit, fake_logit] and computes manipulation probability as
    P(fake) = exp(fake_logit) / (exp(real_logit) + exp(fake_logit))
"""
from __future__ import annotations

import logging
from typing import Literal

import timm
import torch
import torch.nn as nn

log = logging.getLogger(__name__)

BackboneName = Literal["mobilenet_v3_small", "efficientnet_b0"]


class PramanaClassifier(nn.Module):
    """Backbone + binary head. Returns [B, 2] logits."""

    def __init__(self, backbone: BackboneName = "mobilenet_v3_small", pretrained: bool = True):
        super().__init__()
        self.backbone_name = backbone
        if backbone == "mobilenet_v3_small":
            self.body = timm.create_model("mobilenetv3_small_100", pretrained=pretrained, num_classes=0)
            feat_dim = self.body.num_features
        elif backbone == "efficientnet_b0":
            self.body = timm.create_model("efficientnet_b0", pretrained=pretrained, num_classes=0)
            feat_dim = self.body.num_features
        else:
            raise ValueError(f"unknown backbone {backbone}")
        self.dropout = nn.Dropout(0.2)
        self.head = nn.Linear(feat_dim, 2)
        log.info("Built %s backbone with feat_dim=%d", backbone, feat_dim)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        feats = self.body(x)
        feats = self.dropout(feats)
        return self.head(feats)

    @staticmethod
    def manipulation_prob(logits: torch.Tensor) -> torch.Tensor:
        """[B, 2] logits → [B] manipulation probability (P(fake))."""
        probs = torch.softmax(logits, dim=-1)
        return probs[..., 1]
