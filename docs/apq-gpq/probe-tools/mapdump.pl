#!/usr/bin/perl
# Dump portals, reactors, life, areas, info of a Map.wz img.xml (minified one line)
use strict; use warnings;
for my $f (@ARGV) {
  local $/; open my $fh, '<', $f or die "$f: $!"; my $x = <$fh>; close $fh;
  print "=== $f\n";
  my @path; my %cur; my $curKey;
  while ($x =~ m{<(/?)(\w+)([^>]*?)(/?)>}g) {
    my ($close, $tag, $attrs, $self) = ($1, $2, $3, $4);
    if ($tag eq 'imgdir' && !$close) {
      my ($n) = $attrs =~ /name="([^"]*)"/; push @path, $n;
      if (@path == 3 && $path[1] =~ /^(portal|reactor|life|area)$/) { %cur = (); $curKey = "$path[1] $path[2]"; }
      next;
    }
    if ($tag eq 'imgdir' && $close) {
      if (@path == 3 && $path[1] =~ /^(portal|reactor|life|area)$/ && $curKey) {
        my $s = $path[1]; my @k;
        if ($s eq 'portal') { @k = qw(pn pt tm tn script x y); }
        elsif ($s eq 'reactor') { @k = qw(id name x y reactorTime f); }
        elsif ($s eq 'life') { @k = qw(type id x y fh mobTime hide); }
        else { @k = qw(x1 y1 x2 y2); }
        print "$curKey ", join(' ', map { "$_=" . ($cur{$_} // '') } @k), "\n"; $curKey = undef;
      }
      pop @path; next;
    }
    next if $close;
    my ($n) = $attrs =~ /name="([^"]*)"/; my ($v) = $attrs =~ /value="([^"]*)"/;
    if (@path == 2 && $path[1] eq 'info' && defined $v && $n !~ /^(bgm|mapMark|version|cloud|scrollDisable|swim|hideMinimap|VRTop|VRLeft|VRBottom|VRRight|mapDesc|help)$/) { print "info $n=$v\n"; }
    if (@path == 3 && $curKey && defined $n) { $cur{$n} = $v if defined $v; }
  }
}
