use strict; use warnings;
# Stage 2: generateCombo1 distribution
my %dist; my $tot=0;
for my $r (0..242) { my @p=(0,0,0); my $x=$r; while ($x>0){ $p[$x%3]++; $x=int($x/3);} $dist{join(',',@p)}++; $tot++; }
my $solv=0; $solv += $dist{$_} for grep { my @a=split /,/; $a[0]+$a[1]+$a[2]==5 } keys %dist;
printf "stage2: %d distinct combos, %.1f%% have five (solvable)\n", scalar(keys %dist), 100*$solv/$tot;
# candidates: compositions of 5 into 3
my @C; for my $a (0..5){ for my $b (0..5-$a){ push @C,[$a,$b,5-$a-$b]; } }
sub fb2 { my ($g,$s)=@_; my $m=0; for (0..2){ $m++ if $g->[$_]==$s->[$_]; } return $m==0?0:1; } # stock hint: 0 ropes match vs some match (3 match = solved)
sub solved { my ($g,$s)=@_; return "@$g" eq "@$s"; }
# minimax solver over solvable combos, weighted by generator distribution
my %w; for (keys %dist){ my @a=split /,/; next unless $a[0]+$a[1]+$a[2]==5; $w{"@a"}=$dist{$_}; }
my @S = map { [split / /] } keys %w;
sub play { my ($secret)=@_; my @cand=@S; my $n=0; while (1) { $n++; my ($best,$bs)=(undef,1e9);
  for my $g (@C) { my %part; for my $c (@cand){ next if solved($g,$c); $part{fb2($g,$c)} += $w{"@$c"}; } my $worst=0; $worst = $_>$worst?$_:$worst for values %part; my $in = (grep { "@$_" eq "@$g" } @cand) ? 0 : 0.5; if ($worst+$in < $bs){$bs=$worst+$in;$best=$g;} }
  return $n if solved($best,$secret); my $f=fb2($best,$secret); @cand = grep { !solved($best,$_) && fb2($best,$_)==$f } @cand; return 99 if !@cand; } }
my ($sum,$max,$over,$wt)=(0,0,0,0); for my $s (@S){ my $k=play($s); my $ww=$w{"@$s"}; $sum+=$k*$ww; $wt+=$ww; $max=$k if $k>$max; $over+=$ww if $k>7; }
printf "stage2 solver: mean %.2f tries, worst %d, P(>7)=%.1f%%\n", $sum/$wt, $max, 100*$over/$wt;
# Stage 3: 5 of 9, feedback = number of correct platforms
my @P; for my $m (0..511){ my $c=0; my $x=$m; while($x){$c+=$x&1;$x>>=1;} push @P,$m if $c==5; }
sub pc { my $x=shift; my $c=0; while($x){$c+=$x&1;$x>>=1;} return $c; }
my ($s3,$m3)=(0,0);
for my $sec (@P) { my @cand=@P; my $n=0; while (1) { $n++; my ($best,$bs)=(undef,1e9);
  for my $g (@P) { my %part; for my $c (@cand){ next if $c==$g; $part{pc($g&$c)}++; } my $worst=0; $worst=$_>$worst?$_:$worst for values %part; my $adj = (grep {$_==$g} @cand)?0:0.5; if ($worst+$adj<$bs){$bs=$worst+$adj;$best=$g;} }
  last if $best==$sec; my $f=pc($best&$sec); @cand = grep { $_!=$best && pc($best&$_)==$f } @cand; }
  $s3+=$n; $m3=$n if $n>$m3; }
printf "stage3: %d combos; minimax solver mean %.2f tries, worst %d\n", scalar(@P), $s3/@P, $m3;
